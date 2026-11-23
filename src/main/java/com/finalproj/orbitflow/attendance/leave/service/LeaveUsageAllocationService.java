
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveBalance;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveUsageAllocation;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantLockRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveUsageAllocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveUsageAllocationService {

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final LeaveGrantLockRepository grantRepository;
    private final LeaveUsageAllocationRepository allocationRepository;
    private final LeaveBalanceRepository balanceRepository;

    /**
     * 승인된 휴가를 유효한 부여 건에 배분한다.
     *
     * record.status = APPROVED,
     * days 값 저장 완료 후 호출해야 한다.
     *
     * 전자결재 승인 트랜잭션 안에서 호출할 것.
     */
    @Transactional
    public void allocateApprovedLeave(Long recordId) {

        AttendanceRecord record =
                attendanceRecordRepository.findById(recordId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "휴가 기록 없음: " + recordId
                                ));

        if (record.getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException(
                    "최종 승인된 휴가만 차감할 수 있습니다."
            );
        }

        if (!Boolean.TRUE.equals(
                record.getLeaveType().getIsCountable())) {
            return;
        }

        if (record.getEmployee() == null) {
            throw new IllegalStateException(
                    "휴가 신청 사원이 없습니다."
            );
        }

        BigDecimal required = record.getDays();

        if (required == null || required.signum() <= 0) {
            throw new IllegalArgumentException(
                    "휴가 일수가 올바르지 않습니다."
            );
        }

        Long companyId = record.getCompany().getId();
        Long employeeId = record.getEmployee().getId();

        List<LeaveGrant> grants =
                grantRepository.lockEmployeeGrants(
                        companyId,
                        employeeId
                );

        List<LeaveUsageAllocation> allocations =
                allocationRepository.findByRecordIdForUpdate(
                        recordId
                );

        BigDecimal active = allocations.stream()
                .map(LeaveUsageAllocation::getActiveDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (active.signum() > 0) {
            if (active.compareTo(required) == 0) {
                return;
            }

            throw new IllegalStateException(
                    "기존 배분과 승인 휴가 일수가 달라 정산이 필요합니다."
            );
        }

        LocalDate start = record.getStartDate();
        LocalDate end = record.getEndDate();
        LocalDate today =
                LocalDate.now(ZoneId.of("Asia/Seoul"));

        BigDecimal available = grants.stream()
                .filter(g -> eligible(g, start, end, today))
                .map(LeaveGrant::getRemainingDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (available.compareTo(required) < 0) {
            throw new IllegalStateException(
                    "사용 가능한 연차가 부족합니다."
            );
        }

        Map<Long, LeaveUsageAllocation> previous =
                allocations.stream()
                        .collect(Collectors.toMap(
                                LeaveUsageAllocation::getGrantHistoryId,
                                Function.identity()
                        ));

        BigDecimal left = required;

        for (LeaveGrant grant : grants) {

            if (left.signum() == 0) {
                break;
            }

            if (!eligible(grant, start, end, today)) {
                continue;
            }

            BigDecimal portion =
                    grant.getRemainingDays().min(left);

            if (portion.signum() == 0) {
                continue;
            }

            LeaveUsageAllocation previousRow =
                    previous.get(grant.getId());

            if (previousRow != null
                    && previousRow.getActiveDays().signum() != 0) {
                throw new IllegalStateException(
                        "활성 배분이 중복되었습니다."
                );
            }

            grant.deduct(portion);
            changeBalance(grant, portion.negate());

            if (previousRow == null) {
                allocationRepository.save(
                        LeaveUsageAllocation.create(
                                companyId,
                                employeeId,
                                recordId,
                                grant.getId(),
                                portion
                        )
                );
            } else {
                previousRow.reactivate(portion);
            }

            left = left.subtract(portion);
        }

        if (left.signum() != 0) {
            throw new IllegalStateException(
                    "연차 차감 중 불일치가 발생했습니다."
            );
        }
    }

    /**
     * 승인 취소 시 기존 차감 기록을 복구한다.
     *
     * 만료된 연차는 별도 정산 대상으로 처리한다.
     */
    @Transactional
    public void releaseLeave(Long recordId) {

        AttendanceRecord record =
                attendanceRecordRepository.findById(recordId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "휴가 기록 없음: " + recordId
                                ));

        if (record.getEmployee() == null) {
            throw new IllegalStateException(
                    "휴가 신청 사원이 없습니다."
            );
        }

        Long companyId = record.getCompany().getId();
        Long employeeId = record.getEmployee().getId();

        List<LeaveGrant> grants =
                grantRepository.lockEmployeeGrants(
                        companyId,
                        employeeId
                );

        Map<Long, LeaveGrant> grantMap =
                grants.stream()
                        .collect(Collectors.toMap(
                                LeaveGrant::getId,
                                Function.identity()
                        ));

        List<LeaveUsageAllocation> allocations =
                allocationRepository.findByRecordIdForUpdate(
                        recordId
                );

        LocalDate today =
                LocalDate.now(ZoneId.of("Asia/Seoul"));

        // 복구 가능한 상태인지 먼저 확인
        for (LeaveUsageAllocation allocation : allocations) {

            if (allocation.getActiveDays().signum() == 0) {
                continue;
            }

            LeaveGrant grant =
                    grantMap.get(allocation.getGrantHistoryId());

            if (grant == null
                    || Boolean.TRUE.equals(grant.getIsExpired())
                    || grant.getExpirationDate().isBefore(today)) {

                throw new IllegalStateException(
                        "만료된 연차의 취소는 별도 정산이 필요합니다."
                );
            }
        }

        for (LeaveUsageAllocation allocation : allocations) {

            BigDecimal days = allocation.getActiveDays();

            if (days.signum() == 0) {
                continue;
            }

            LeaveGrant grant =
                    grantMap.get(allocation.getGrantHistoryId());

            grant.restore(days);
            changeBalance(grant, days);
            allocation.releaseAll();
        }
    }

    private boolean eligible(
            LeaveGrant grant,
            LocalDate start,
            LocalDate end,
            LocalDate today
    ) {
        return !Boolean.TRUE.equals(grant.getIsExpired())
                && grant.getRemainingDays().signum() > 0
                && !grant.getGrantDate().isAfter(start)
                && !grant.getExpirationDate().isBefore(end)
                && !grant.getExpirationDate().isBefore(today);
    }

    private void changeBalance(
            LeaveGrant grant,
            BigDecimal delta
    ) {

        LeaveBalance balance =
                balanceRepository.findByCompanyIdAndEmployeeIdAndYear(
                        grant.getCompanyId(),
                        grant.getEmployeeId(),
                        grant.getGrantDate().getYear()
                ).orElseThrow(() ->
                        new IllegalStateException(
                                "부여 연도별 잔여 합계 데이터가 없습니다."
                        ));

        balance.changeRemaining(delta);
    }
}
