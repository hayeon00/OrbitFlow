
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.attendance.leave.service.AnnualAttendanceService;
import com.finalproj.orbitflow.attendance.leave.service.MonthlyAttendanceService;
import com.finalproj.orbitflow.attendance.leave.service.LeaveUsageAllocationService;

import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegularAnnualLeaveGrantTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate GRANT_DATE =
            LocalDate.of(2025, 3, 10);

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
    private CommuteRepository commuteRepository;

    @Mock
    private AttendanceEventRepository attendanceEventRepository;

    @Mock
    private MonthlyAttendanceService monthlyAttendanceService;

    @Mock
    private LeaveUsageAllocationService leaveUsageAllocationService;

    @Mock
    private AnnualAttendanceService annualAttendanceService;

    // 실제 연차 일수 계산 정책을 실행한다.
    @Spy
    private AnnualLeaveAccrualPolicy annualLeaveAccrualPolicy =
            new AnnualLeaveAccrualPolicy();

    @InjectMocks
    private RegularLeaveAccrualService leaveService;

    @BeforeEach
    void connectGrantWriter() {

        // 실제 저장 로직(LeaveGrantWriter)을 Mock 저장소와 연결한다.
        ReflectionTestUtils.setField(
                leaveService,
                "leaveGrantWriter",
                new LeaveGrantWriter(
                        leaveGrantRepository,
                        leaveBalanceRepository
                )
        );
    }

    /**
     * 재직 중인 사원과 회사 정보를 설정한다.
     *
     * 회사 ID는 실제 연차를 부여하지 않는 경우에도
     * 연간 근태 평가를 위해 필요하다.
     */
    private Employee givenActiveEmployee(LocalDate hireDate) {

        Employee employee = mock(Employee.class);
        Company company = mock(Company.class);

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(employee));

        when(employee.getStatus())
                .thenReturn(EmployeeStatus.ACTIVE);

        when(employee.getHireDate())
                .thenReturn(hireDate);

        when(employee.getCompany())
                .thenReturn(company);

        when(company.getId())
                .thenReturn(COMPANY_ID);

        return employee;
    }

    /**
     * 연간 근태 집계 결과를 설정한다.
     *
     * 연차 일수 계산은 Mock으로 고정하지 않고
     * 실제 AnnualLeaveAccrualPolicy에 맡긴다.
     */
    private void givenAnnualAttendance(
            LocalDate periodStart,
            int scheduledDays,
            int attendedDays,
            int undeterminedDays
    ) {

        when(annualAttendanceService.evaluate(
                COMPANY_ID,
                EMPLOYEE_ID,
                periodStart,
                GRANT_DATE.minusDays(1)
        )).thenReturn(
                new AnnualAttendanceService.AnnualAttendanceSummary(
                        scheduledDays,
                        attendedDays,
                        undeterminedDays
                )
        );
    }

    /**
     * 정기 연차 발생 이력 및 연차 잔여량 증가 호출을 검증한다.
     */
    private void verifyGranted(int days) {

        verify(leaveGrantRepository).saveAndFlush(
                argThat(grant ->
                        grant != null
                                && "ANNUAL_REGULAR"
                                .equals(grant.getGrantType())
                                && GRANT_DATE.equals(grant.getGrantDate())
                                && grant.getGrantedDays()
                                .compareTo(BigDecimal.valueOf(days)) == 0
                )
        );

        verify(leaveBalanceRepository)
                .addGrantedDaysAtomically(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        GRANT_DATE.getYear(),
                        BigDecimal.valueOf(days)
                );
    }

    /**
     * 정기 연차가 발생하지 않아야 하는 경우
     * 발생 이력 및 연차 잔여량 저장을 차단했는지 검증한다.
     */
    private void verifyNotGranted() {

        verify(leaveGrantRepository, never())
                .saveAndFlush(any(LeaveGrant.class));

        verify(leaveBalanceRepository, never())
                .addGrantedDaysAtomically(
                        anyLong(),
                        anyLong(),
                        anyInt(),
                        any(BigDecimal.class)
                );
    }

    @Test
    void 입사1주년_출근율80퍼센트면_15일을_부여한다() {

        LocalDate hire = LocalDate.of(2024, 3, 10);

        Employee employee = givenActiveEmployee(hire);

        givenAnnualAttendance(
                hire,
                200,
                160,
                0
        );

        leaveService.grantRegularAnnualLeave(
                EMPLOYEE_ID,
                GRANT_DATE
        );

        verifyGranted(15);
    }

    @Test
    void 입사3주년_출근율80퍼센트이상이면_16일을_부여한다() {

        LocalDate hire = LocalDate.of(2022, 3, 10);

        Employee employee = givenActiveEmployee(hire);

        givenAnnualAttendance(
                LocalDate.of(2024, 3, 10),
                200,
                190,
                0
        );

        leaveService.grantRegularAnnualLeave(
                EMPLOYEE_ID,
                GRANT_DATE
        );

        verifyGranted(16);
    }

    @Test
    void 출근율80퍼센트미만이면_정기연차를_지급하지않는다() {

        LocalDate hire = LocalDate.of(2024, 3, 10);

        givenActiveEmployee(hire);

        givenAnnualAttendance(
                hire,
                200,
                159,
                0
        );

        leaveService.grantRegularAnnualLeave(
                EMPLOYEE_ID,
                GRANT_DATE
        );

        verifyNotGranted();
    }

    @Test
    void 근태미확정일이_있으면_정기연차를_보류한다() {

        LocalDate hire = LocalDate.of(2024, 3, 10);

        givenActiveEmployee(hire);

        givenAnnualAttendance(
                hire,
                200,
                199,
                1
        );

        leaveService.grantRegularAnnualLeave(
                EMPLOYEE_ID,
                GRANT_DATE
        );

        verifyNotGranted();
    }

    @Test
    void 이미_정기연차가_부여되었다면_중복지급하지않는다() {

        LocalDate hire = LocalDate.of(2024, 3, 10);

        // 중복 확인 후 즉시 종료되는 경로이므로
        // 회사 정보 및 사원 ID를 불필요하게 설정하지 않는다.
        Employee employee = mock(Employee.class);

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(employee));

        when(employee.getStatus())
                .thenReturn(EmployeeStatus.ACTIVE);

        when(employee.getHireDate())
                .thenReturn(hire);

        when(leaveGrantRepository
                .existsByEmployeeIdAndGrantTypeAndGrantDate(
                        EMPLOYEE_ID,
                        "ANNUAL_REGULAR",
                        GRANT_DATE
                )).thenReturn(true);

        leaveService.grantRegularAnnualLeave(
                EMPLOYEE_ID,
                GRANT_DATE
        );

        verify(annualAttendanceService, never())
                .evaluate(
                        anyLong(),
                        anyLong(),
                        any(LocalDate.class),
                        any(LocalDate.class)
                );

        verifyNotGranted();
    }
}
