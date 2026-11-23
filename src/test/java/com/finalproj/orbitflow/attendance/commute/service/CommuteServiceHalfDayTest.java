package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.service.LeaveTimePolicyService;
import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.exception.BusinessException;
import com.finalproj.orbitflow.global.time.attendance.TimeProvider;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommuteServiceHalfDayTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 7);

    @Mock private CommuteRepository commuteRepository;
    @Mock private AttendanceRuleRepository attendanceRuleRepository;
    @Mock private EmployeeRuleRepository employeeRuleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository attendanceEventRepository;
    @Mock private TimeProvider timeProvider;
    @Mock private AttendanceRecordRepository attendanceRecordRepository;
    @Mock private LeaveTimePolicyService leaveTimePolicyService;

    @InjectMocks private CommuteService service;

    private void timeAt(int hour, int minute) {
        when(timeProvider.nowDateTime()).thenReturn(DATE.atTime(hour, minute));
    }

    private void regularRule() {
        // 별도 사원 규칙이 없으면 회사 기본 09:00~18:00
        when(employeeRuleRepository.findActiveRuleByEmployeeIdAndDate(EMPLOYEE_ID, DATE))
                .thenReturn(Optional.empty());
        AttendanceRule companyRule = mock(AttendanceRule.class);
        when(companyRule.getDefaultStartTime()).thenReturn(LocalTime.of(9, 0));
        when(companyRule.getDefaultEndTime()).thenReturn(LocalTime.of(18, 0));
        when(attendanceRuleRepository.findByCompanyIdAndIsDefaultTrue(COMPANY_ID))
                .thenReturn(Optional.of(companyRule));
    }

    private void approvedHalf(String type) {
        AttendanceRecord record = mock(AttendanceRecord.class);
        LeaveType leaveType = mock(LeaveType.class);
        when(record.getLeaveType()).thenReturn(leaveType);
        when(leaveType.getAttendancePolicy()).thenReturn(LeaveAttendancePolicy.PARTIAL_DAY);
        when(leaveType.getTypeName()).thenReturn(type);
        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID, EMPLOYEE_ID, DocumentStatus.APPROVED, DATE, DATE))
                .thenReturn(List.of(record));
    }

    private void noApprovedHalf() {
        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID, EMPLOYEE_ID, DocumentStatus.APPROVED, DATE, DATE))
                .thenReturn(List.of());
    }

    private void halfPolicy(String type) {
        LeaveTimePolicy policy = mock(LeaveTimePolicy.class);
        if ("오전반차".equals(type)) {
            when(policy.getMorningHalfReturnTime()).thenReturn(LocalTime.of(14, 0));
        } else {
            when(policy.getAfternoonHalfLeaveTime()).thenReturn(LocalTime.of(14, 0));
        }
        when(leaveTimePolicyService.getPolicy(COMPANY_ID)).thenReturn(policy);
    }

    private void existingAttendanceForCheckIn() {
        Attendance attendance = mock(Attendance.class);
        when(commuteRepository.findByCompanyIdAndEmployeeIdAndWorkDate(
                COMPANY_ID, EMPLOYEE_ID, DATE)).thenReturn(Optional.of(attendance));
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(mock(Employee.class)));
    }

    @Test
    void 오전반차_14시_출근은_ON_TIME() {
        timeAt(14, 0);
        regularRule();
        approvedHalf("오전반차");
        halfPolicy("오전반차");
        existingAttendanceForCheckIn();

        service.checkIn(COMPANY_ID, EMPLOYEE_ID, null);

        verify(commuteRepository).insertCheckInIgnoreDuplicate(
                eq(COMPANY_ID), eq(EMPLOYEE_ID), eq(DATE),
                eq(DATE.atTime(14, 0)), eq("ON_TIME"));
    }

    @Test
    void 오전반차_14시20분_출근은_LATE() {
        timeAt(14, 20);
        regularRule();
        approvedHalf("오전반차");
        halfPolicy("오전반차");
        existingAttendanceForCheckIn();

        service.checkIn(COMPANY_ID, EMPLOYEE_ID, null);

        verify(commuteRepository).insertCheckInIgnoreDuplicate(
                eq(COMPANY_ID), eq(EMPLOYEE_ID), eq(DATE),
                eq(DATE.atTime(14, 20)), eq("LATE"));
    }

    @Test
    void 일반출근_9시는_ON_TIME() {
        timeAt(9, 0);
        regularRule();
        noApprovedHalf();
        existingAttendanceForCheckIn();

        service.checkIn(COMPANY_ID, EMPLOYEE_ID, null);

        verify(commuteRepository).insertCheckInIgnoreDuplicate(
                eq(COMPANY_ID), eq(EMPLOYEE_ID), eq(DATE),
                eq(DATE.atTime(9, 0)), eq("ON_TIME"));
        verifyNoInteractions(leaveTimePolicyService);
    }

    @Test
    void 오후반차_14시_퇴근은_허용() {
        timeAt(14, 0);
        regularRule();
        approvedHalf("오후반차");
        halfPolicy("오후반차");
        when(commuteRepository.updateLeaveAtIfNull(
                COMPANY_ID, EMPLOYEE_ID, DATE, DATE.atTime(14, 0))).thenReturn(1);
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(mock(Employee.class)));
        when(commuteRepository.findByCompanyIdAndEmployeeIdAndWorkDate(
                COMPANY_ID, EMPLOYEE_ID, DATE)).thenReturn(Optional.of(mock(Attendance.class)));

        service.checkOut(COMPANY_ID, EMPLOYEE_ID);

        verify(commuteRepository).updateLeaveAtIfNull(
                COMPANY_ID, EMPLOYEE_ID, DATE, DATE.atTime(14, 0));
    }

    @Test
    void 오후반차_13시59분_퇴근은_거부() {
        timeAt(13, 59);
        regularRule();
        approvedHalf("오후반차");
        halfPolicy("오후반차");

        assertThrows(BusinessException.class,
                () -> service.checkOut(COMPANY_ID, EMPLOYEE_ID));

        verify(commuteRepository, never()).updateLeaveAtIfNull(
                anyLong(), anyLong(), any(LocalDate.class), any(LocalDateTime.class));
    }

    @Test
    void 일반직원_14시_퇴근은_거부() {
        timeAt(14, 0);
        regularRule();
        noApprovedHalf();

        assertThrows(BusinessException.class,
                () -> service.checkOut(COMPANY_ID, EMPLOYEE_ID));

        verifyNoInteractions(leaveTimePolicyService);
    }

    @Test
    void 승인된_오전반차라도_퇴근은_18시_이전_거부() {
        timeAt(14, 0);
        regularRule();
        approvedHalf("오전반차");

        assertThrows(BusinessException.class,
                () -> service.checkOut(COMPANY_ID, EMPLOYEE_ID));

        verifyNoInteractions(leaveTimePolicyService);
    }
}
