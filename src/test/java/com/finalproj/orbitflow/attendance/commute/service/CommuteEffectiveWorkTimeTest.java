
package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.service.LeaveTimePolicyService;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class CommuteEffectiveWorkTimeTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate DATE =
            LocalDate.of(2026, 11, 3);

    @Mock
    private CommuteRepository commuteRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private AttendanceEventRepository attendanceEventRepository;

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @Mock
    private LeaveTimePolicyService leaveTimePolicyService;

    @Mock
    private EffectiveAttendanceRuleService effectiveAttendanceRuleService;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private CommuteService service;

    private void timeAt(int hour, int minute) {
        when(timeProvider.nowDateTime())
                .thenReturn(DATE.atTime(hour, minute));
    }

    private void employeeRule() {
        EffectiveAttendanceRule rule =
                new EffectiveAttendanceRule(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DATE,
                        LocalTime.of(10, 0),
                        LocalTime.of(19, 0),
                        LocalTime.of(12, 0),
                        LocalTime.of(13, 0),
                        60,
                        true,
                        200L
                );

        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID,
                EMPLOYEE_ID,
                DATE
        )).thenReturn(rule);
    }

    private void noApprovedHalfDay() {
        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                DATE,
                DATE
        )).thenReturn(List.of());
    }

    private void existingAttendance() {
        when(commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DATE
                ))
                .thenReturn(Optional.of(mock(Attendance.class)));

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(mock(Employee.class)));
    }

    @Test
    void 직원별_10시출근_규칙에서_9시59분은_차단된다() {
        timeAt(9, 59);
        employeeRule();

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        null
                )
        );

        verify(commuteRepository, never())
                .insertCheckInIgnoreDuplicate(
                        anyLong(),
                        anyLong(),
                        any(LocalDate.class),
                        any(),
                        anyString()
                );
    }

    @Test
    void 직원별_10시출근_규칙에서_10시는_정상출근이다() {
        timeAt(10, 0);
        employeeRule();
        noApprovedHalfDay();
        existingAttendance();

        service.checkIn(
                COMPANY_ID,
                EMPLOYEE_ID,
                null
        );

        verify(commuteRepository)
                .insertCheckInIgnoreDuplicate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DATE,
                        DATE.atTime(10, 0),
                        "ON_TIME"
                );
    }

    @Test
    void 직원별_10시출근_규칙에서_10시1분은_지각이다() {
        timeAt(10, 1);
        employeeRule();
        noApprovedHalfDay();
        existingAttendance();

        service.checkIn(
                COMPANY_ID,
                EMPLOYEE_ID,
                null
        );

        verify(commuteRepository)
                .insertCheckInIgnoreDuplicate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DATE,
                        DATE.atTime(10, 1),
                        "LATE"
                );
    }

    @Test
    void 직원별_19시퇴근_규칙에서_19시는_퇴근허용된다() {
        timeAt(19, 0);
        employeeRule();
        noApprovedHalfDay();

        when(commuteRepository.updateLeaveAtIfNull(
                COMPANY_ID,
                EMPLOYEE_ID,
                DATE,
                DATE.atTime(19, 0)
        )).thenReturn(1);

        when(commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DATE
                ))
                .thenReturn(Optional.of(mock(Attendance.class)));

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(mock(Employee.class)));

        service.checkOut(
                COMPANY_ID,
                EMPLOYEE_ID
        );

        verify(commuteRepository)
                .updateLeaveAtIfNull(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DATE,
                        DATE.atTime(19, 0)
                );
    }
}
