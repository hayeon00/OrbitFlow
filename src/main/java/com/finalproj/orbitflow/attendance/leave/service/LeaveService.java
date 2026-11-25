
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.entity.Document;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.WorkStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 전자결재 연동 진입점.
 *
 * 결재 승인·취소 시 연차 차감/복구와 사원 근무상태 변경을 처리한다.
 * (ApprovalEventTxService에서 호출)
 *
 * 관련 서비스:
 * - 연차 발생: accrual 패키지 (Monthly/RegularLeaveAccrualService)
 * - 조회: LeaveQueryService
 * - 일일 근무상태 동기화: LeaveWorkStatusSyncService
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeaveService {

    private final EmployeeRepository employeeRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final LeaveUsageAllocationService leaveUsageAllocationService;

    /**
     * 기존 전자결재의 연차 차감 호출과 연결.
     *
     * Document와 AttendanceRecord가 모두
     * APPROVED로 저장된 뒤 실행해야 함.
     */
    @Transactional
    public void deduction(
            Employee employee,
            BigDecimal days,
            Document document,
            LeaveType leaveType
    ) {

        if (!Boolean.TRUE.equals(
                leaveType.getIsCountable())) {
            return;
        }

        if (document.getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException(
                    "최종 승인된 문서만 연차를 차감할 수 있습니다."
            );
        }

        AttendanceRecord record =
                attendanceRecordRepository
                        .findBySourceDocument_Id(document.getId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "승인 문서에 연결된 근태 기록이 없습니다: "
                                                + document.getId()
                                ));

        if (record.getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException(
                    "근태 기록의 승인 상태가 아직 반영되지 않았습니다."
            );
        }

        if (record.getEmployee() == null
                || !record.getEmployee().getId()
                .equals(employee.getId())
                || !record.getCompany().getId()
                .equals(employee.getCompany().getId())
                || record.getDays().compareTo(days) != 0
                || !record.getLeaveType().getId()
                .equals(leaveType.getId())) {

            throw new IllegalStateException(
                    "결재 요청과 근태 기록이 일치하지 않습니다."
            );
        }

        leaveUsageAllocationService.allocateApprovedLeave(
                record.getId()
        );
    }

    /**
     * 승인된 휴가의 차감 취소.
     */
    @Transactional
    public void releaseApprovedLeave(
            Long attendanceRecordId
    ) {
        leaveUsageAllocationService.releaseLeave(
                attendanceRecordId
        );
    }

    /**
     * 사원의 근무 상태 변경.
     */
    @Transactional
    public void updateWorkStatus(
            Long employeeId,
            WorkStatus status
    ) {

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        employee.updateWorkStatus(status);
    }
}
