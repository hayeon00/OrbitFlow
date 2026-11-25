
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.dto.request.LeaveSearchReqDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveBalanceResDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveHistoryResDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveRemainingResDto;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveBalance;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 연차 요약·잔여량·휴가 사용 이력 조회 (읽기 전용).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeaveQueryService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final EmployeeRepository employeeRepository;
    private final LeaveGrantRepository leaveGrantRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;

    private LeaveBalance createInitialBalance(
            Long companyId,
            Long employeeId,
            int year
    ) {

        return LeaveBalance.builder()
                .companyId(companyId)
                .employeeId(employeeId)
                .year(year)
                .totalGranted(BigDecimal.ZERO)
                .remainingDays(BigDecimal.ZERO)
                .build();
    }

    /**
     * 연차 요약 조회.
     */
    public LeaveBalanceResDto getMySummary(
            Long companyId,
            Long employeeId,
            Integer year
    ) {

        int targetYear = year == null
                ? LocalDate.now(KOREA).getYear()
                : year;

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        if (!employee.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException(
                    "회사 정보가 일치하지 않습니다."
            );
        }

        LeaveBalance balance =
                leaveBalanceRepository
                        .findByCompanyIdAndEmployeeIdAndYear(
                                companyId,
                                employeeId,
                                targetYear
                        )
                        .orElseGet(() ->
                                createInitialBalance(
                                        companyId,
                                        employeeId,
                                        targetYear
                                ));

        BigDecimal granted = balance.getTotalGranted();

        BigDecimal remaining =
                leaveGrantRepository.findAll().stream()
                        .filter(g ->
                                g.getCompanyId().equals(companyId))
                        .filter(g ->
                                g.getEmployeeId().equals(employeeId))
                        .filter(g ->
                                g.getGrantDate().getYear() == targetYear)
                        .filter(g ->
                                !Boolean.TRUE.equals(g.getIsExpired()))
                        .filter(g ->
                                !g.getExpirationDate().isBefore(
                                        LocalDate.now(KOREA)))
                        .map(LeaveGrant::getRemainingDays)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal used =
                attendanceRecordRepository
                        .findByCompanyIdAndEmployeeId(
                                companyId,
                                employeeId
                        )
                        .stream()
                        .filter(r ->
                                r.getStatus() == DocumentStatus.APPROVED)
                        .filter(r ->
                                r.getLeaveType() != null
                                        && Boolean.TRUE.equals(
                                        r.getLeaveType().getIsCountable()))
                        .filter(r ->
                                r.getStartDate().getYear() == targetYear)
                        .map(AttendanceRecord::getDays)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        return LeaveBalanceResDto.builder()
                .year(targetYear)
                .totalGranted(granted)
                .remainingDays(remaining)
                .usedDays(used)
                .hireDate(employee.getHireDate())
                .build();
    }

    /**
     * 전체 휴가 신청 내역 조회.
     */
    public Page<LeaveHistoryResDto> getAllLeaveHistory(
            Long companyId,
            Long employeeId,
            LeaveSearchReqDto searchDto,
            Pageable pageable
    ) {

        return attendanceRecordRepository
                .findAllLeaveHistoryWithFilters(
                        companyId,
                        employeeId,
                        searchDto.getTypeName(),
                        searchDto.getStatus(),
                        searchDto.getStartDate(),
                        searchDto.getEndDate(),
                        pageable
                )
                .map(this::mapRecordToDto);
    }

    /**
     * 휴가 사용 이력 조회.
     */
    public Page<LeaveHistoryResDto> getLeaveUsageHistory(
            Long companyId,
            Long employeeId,
            int year,
            String typeName,
            DocumentStatus status,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {

        return attendanceRecordRepository
                .findUsageHistoryWithFilters(
                        companyId,
                        employeeId,
                        year,
                        typeName,
                        status,
                        startDate,
                        endDate,
                        pageable
                )
                .map(this::mapRecordToDto);
    }

    private LeaveHistoryResDto mapRecordToDto(
            AttendanceRecord r
    ) {

        String typeName =
                r.getLeaveType() != null
                        ? r.getLeaveType().getTypeName()
                        : "미지정";

        String actionDate =
                r.getCreatedAt()
                        .atZone(KOREA)
                        .toLocalDate()
                        .toString();

        return LeaveHistoryResDto.builder()
                .title(typeName)
                .actionDate(actionDate)
                .period(
                        r.getStartDate()
                                + " ~ "
                                + (r.getEndDate() != null
                                ? r.getEndDate()
                                : r.getStartDate())
                )
                .days(r.getDays())
                .type("USED")
                .statusName(mapStatusText(r.getStatus()))
                .statusCode(r.getStatus().name())
                .reason(r.getReason())
                .typeDescription(
                        r.getLeaveType() != null
                                ? r.getLeaveType().getDescription()
                                : ""
                )
                .build();
    }

    private String mapStatusText(DocumentStatus status) {

        if (status == null) {
            return "대기";
        }

        return switch (status) {
            case APPROVED -> "승인";
            case REJECTED -> "반려";
            default -> "대기";
        };
    }

    /**
     * 현재 사용 가능한 연차 잔여량.
     */
    public LeaveRemainingResDto getLeaveRemaining(
            Long employeeId
    ) {

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        BigDecimal remaining =
                leaveGrantRepository.findAll().stream()
                        .filter(g ->
                                g.getEmployeeId().equals(employeeId))
                        .filter(g ->
                                g.getCompanyId().equals(
                                        employee.getCompany().getId()))
                        .filter(g ->
                                !Boolean.TRUE.equals(g.getIsExpired()))
                        .filter(g ->
                                !g.getExpirationDate().isBefore(
                                        LocalDate.now(KOREA)))
                        .map(LeaveGrant::getRemainingDays)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        return new LeaveRemainingResDto(remaining);
    }
}
