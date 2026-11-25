
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;
import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.entity.Document;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.enums.WorkStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveWorkStatusSyncTest {

    private static final LocalDate NOV_2 =
            LocalDate.of(2026, 11, 2);

    private static final LocalDate NOV_3 =
            LocalDate.of(2026, 11, 3);

    private static final LocalDate NOV_4 =
            LocalDate.of(2026, 11, 4);

    private static final LocalDate NOV_5 =
            LocalDate.of(2026, 11, 5);

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private LeaveGrantRepository leaveGrantRepository;

    @Mock
    private LeaveBalanceRepository leaveBalanceRepository;

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @Mock
    private AttendanceEventRepository attendanceEventRepository;

    @Mock
    private CommuteRepository commuteRepository;

    @Mock
    private MonthlyAttendanceService monthlyAttendanceService;

    @Mock
    private LeaveUsageAllocationService leaveUsageAllocationService;

    @InjectMocks
    private LeaveWorkStatusSyncService leaveService;

    /**
     * Employee 엔티티의 실제 상태 변경을
     * Mockito 환경에서 재현한다.
     */
    private Employee employeeWithStatus(
            Long employeeId,
            WorkStatus initialStatus
    ) {

        Employee employee = mock(Employee.class);

        AtomicReference<WorkStatus> currentStatus =
                new AtomicReference<>(initialStatus);

        lenient().when(employee.getId())
                .thenReturn(employeeId);

        lenient().when(employee.getStatus())
                .thenReturn(EmployeeStatus.ACTIVE);

        lenient().when(employee.getWorkStatus())
                .thenAnswer(invocation -> currentStatus.get());

        lenient().doAnswer(invocation -> {
            WorkStatus nextStatus =
                    invocation.getArgument(0);

            currentStatus.set(nextStatus);
            return null;
        }).when(employee).updateWorkStatus(
                any(WorkStatus.class)
        );

        return employee;
    }


    /**
     * 승인된 출장 또는 외근 이벤트.
     */
    private AttendanceEvent workEvent(
            Employee employee,
            BaseRole role
    ) {

        AttendanceEvent event =
                mock(AttendanceEvent.class);

        when(event.getEmployee())
                .thenReturn(employee);

        // 종일연차가 우선인 경우에는
        // 출장·외근 유형을 조회하지 않을 수 있음
        if (role != null) {
            lenient().when(event.getBaseRole())
                    .thenReturn(role);
        }

        return event;
    }


    /**
     * 승인된 휴가 기록.
     */
    private AttendanceRecord approvedLeave(
            Employee employee,
            LeaveAttendancePolicy policy
    ) {

        AttendanceRecord record =
                mock(AttendanceRecord.class);

        LeaveType leaveType =
                mock(LeaveType.class);

        Document document =
                mock(
                        Document.class,
                        RETURNS_DEEP_STUBS
                );

        when(record.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(record.getEmployee())
                .thenReturn(employee);

        when(record.getLeaveType())
                .thenReturn(leaveType);

        when(leaveType.getAttendancePolicy())
                .thenReturn(policy);

        // 종일 휴가일 때에만 문서의 BaseRole 확인
        if (policy == LeaveAttendancePolicy.ATTENDANCE_CREDIT) {

            when(record.getSourceDocument())
                    .thenReturn(document);

            when(document.getTemplateGroup().getBaseRole())
                    .thenReturn(BaseRole.VACATION);
        }

        return record;
    }

    @Test
    void 출장_기간_둘째날에도_출장상태를_유지한다() {

        Employee employee = employeeWithStatus(
                10L,
                WorkStatus.OFF_WORK
        );

        AttendanceEvent event = workEvent(
                employee,
                BaseRole.BUSINESS_TRIP
        );

        when(attendanceEventRepository
                .findApprovedActiveWorkEventsForDailySync(
                        eq(List.of(
                                BaseRole.BUSINESS_TRIP,
                                BaseRole.OUTWORK
                        )),
                        eq(DocumentStatus.APPROVED),
                        eq(NOV_3)
                ))
                .thenReturn(List.of(event));

        leaveService.updateAllEmployeesWorkStatus(NOV_3);

        assertEquals(
                WorkStatus.BUSINESS_TRIP,
                employee.getWorkStatus()
        );

        verify(employee).updateWorkStatus(
                WorkStatus.BUSINESS_TRIP
        );
    }

    @Test
    void 외근_기간_둘째날에도_외근상태를_유지한다() {

        Employee employee = employeeWithStatus(
                20L,
                WorkStatus.OFF_WORK
        );

        AttendanceEvent event = workEvent(
                employee,
                BaseRole.OUTWORK
        );

        when(attendanceEventRepository
                .findApprovedActiveWorkEventsForDailySync(
                        anyList(),
                        eq(DocumentStatus.APPROVED),
                        eq(NOV_3)
                ))
                .thenReturn(List.of(event));

        leaveService.updateAllEmployeesWorkStatus(NOV_3);

        assertEquals(
                WorkStatus.OUTWORK,
                employee.getWorkStatus()
        );

        verify(employee).updateWorkStatus(
                WorkStatus.OUTWORK
        );
    }

    @Test
    void 출장_기간_종료_다음날에는_출장상태를_해제한다() {

        Employee employee = employeeWithStatus(
                30L,
                WorkStatus.BUSINESS_TRIP
        );

        when(employeeRepository
                .findByStatusAndWorkStatusIn(
                        eq(EmployeeStatus.ACTIVE),
                        anyList()
                ))
                .thenReturn(List.of(employee));

        // 11월 5일에는 유효한 출장 이벤트가 없음
        leaveService.updateAllEmployeesWorkStatus(NOV_5);

        assertEquals(
                WorkStatus.OFF_WORK,
                employee.getWorkStatus()
        );

        verify(employee).updateWorkStatus(
                WorkStatus.OFF_WORK
        );

        verify(employee, never()).updateWorkStatus(
                WorkStatus.BUSINESS_TRIP
        );
    }

    @Test
    void 승인된_오전반차는_종일_휴가상태로_변경하지_않는다() {

        Employee employee = employeeWithStatus(
                40L,
                WorkStatus.OFF_WORK
        );

        AttendanceRecord halfDay = approvedLeave(
                employee,
                LeaveAttendancePolicy.PARTIAL_DAY
        );

        when(attendanceRecordRepository
                .findActiveAttendanceRecords(NOV_3))
                .thenReturn(List.of(halfDay));

        leaveService.updateAllEmployeesWorkStatus(NOV_3);

        assertEquals(
                WorkStatus.OFF_WORK,
                employee.getWorkStatus()
        );

        verify(employee, never()).updateWorkStatus(
                WorkStatus.VACATION
        );
    }

    @Test
    void 종일연차와_출장_충돌시_종일연차를_우선한다() {

        Employee employee = employeeWithStatus(
                50L,
                WorkStatus.OFF_WORK
        );

        AttendanceRecord annualLeave = approvedLeave(
                employee,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT
        );

        AttendanceEvent businessTrip = workEvent(
                employee,
                BaseRole.BUSINESS_TRIP
        );

        when(attendanceRecordRepository
                .findActiveAttendanceRecords(NOV_3))
                .thenReturn(List.of(annualLeave));

        when(attendanceEventRepository
                .findApprovedActiveWorkEventsForDailySync(
                        anyList(),
                        eq(DocumentStatus.APPROVED),
                        eq(NOV_3)
                ))
                .thenReturn(List.of(businessTrip));

        leaveService.updateAllEmployeesWorkStatus(NOV_3);

        assertEquals(
                WorkStatus.VACATION,
                employee.getWorkStatus()
        );

        verify(employee).updateWorkStatus(
                WorkStatus.VACATION
        );

        verify(employee, never()).updateWorkStatus(
                WorkStatus.BUSINESS_TRIP
        );
    }
}
