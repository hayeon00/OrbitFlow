
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RegularLeaveAccrualBatchServiceTest {

    private static final Long COMPANY_ID = 1L;

    private static final LocalDate TODAY =
            LocalDate.of(2026, 11, 5);

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private RegularLeaveAccrualService leaveService;

    @InjectMocks
    private RegularLeaveAccrualBatchService batchService;

    @BeforeEach
    void setUp() {

        Company company = mock(Company.class);

        when(company.getId())
                .thenReturn(COMPANY_ID);

        when(companyRepository.findAll())
                .thenReturn(List.of(company));
    }

    private Employee employee(
            Long employeeId,
            LocalDate hireDate
    ) {

        Employee employee = mock(Employee.class);

        when(employee.getHireDate())
                .thenReturn(hireDate);

        return employee;
    }

    @Test
    void 가장_최근_입사기념일을_발생일로_정기연차_부여를_요청한다() {

        Employee first =
                employee(10L, LocalDate.of(2025, 11, 5));

        Employee second =
                employee(20L, LocalDate.of(2023, 11, 5));

        // 오늘이 기념일이 아니어도 직전 기념일(2025-11-06)분을 재시도한다.
        Employee afterAnniversary =
                employee(30L, LocalDate.of(2024, 11, 6));

        when(first.getId()).thenReturn(10L);
        when(second.getId()).thenReturn(20L);
        when(afterAnniversary.getId()).thenReturn(30L);

        when(employeeRepository.findByCompanyIdAndStatus(
                COMPANY_ID,
                EmployeeStatus.ACTIVE
        )).thenReturn(List.of(
                first,
                second,
                afterAnniversary
        ));

        batchService.run(TODAY);

        verify(leaveService).grantRegularAnnualLeave(10L, TODAY);
        verify(leaveService).grantRegularAnnualLeave(20L, TODAY);
        verify(leaveService).grantRegularAnnualLeave(
                30L,
                LocalDate.of(2025, 11, 6)
        );

        verifyNoMoreInteractions(leaveService);
    }

    @Test
    void 기념일_당일_배치를_놓쳐도_다음날_같은_발생일로_재시도한다() {

        Employee employee =
                employee(10L, LocalDate.of(2025, 11, 5));

        when(employee.getId()).thenReturn(10L);

        when(employeeRepository.findByCompanyIdAndStatus(
                COMPANY_ID,
                EmployeeStatus.ACTIVE
        )).thenReturn(List.of(employee));

        batchService.run(TODAY.plusDays(1));

        verify(leaveService).grantRegularAnnualLeave(10L, TODAY);
        verifyNoMoreInteractions(leaveService);
    }

    @Test
    void 입사_1주년_전이거나_입사일이_없으면_요청하지_않는다() {

        Employee underOneYear =
                employee(10L, LocalDate.of(2025, 11, 6));

        Employee noHireDate =
                employee(20L, null);

        when(employeeRepository.findByCompanyIdAndStatus(
                COMPANY_ID,
                EmployeeStatus.ACTIVE
        )).thenReturn(List.of(underOneYear, noHireDate));

        batchService.run(TODAY);

        verifyNoInteractions(leaveService);
    }

    @Test
    void 한_직원의_지급이_실패해도_다른_직원은_계속_처리한다() {

        Employee first =
                employee(10L, LocalDate.of(2025, 11, 5));

        Employee second =
                employee(20L, LocalDate.of(2024, 11, 5));

        when(first.getId()).thenReturn(10L);
        when(second.getId()).thenReturn(20L);

        when(employeeRepository.findByCompanyIdAndStatus(
                COMPANY_ID,
                EmployeeStatus.ACTIVE
        )).thenReturn(List.of(first, second));

        // 직원 A의 서비스 실행 중 오류가 발생해도
        // 직원 B의 호출은 계속되어야 한다.
        doThrow(new IllegalStateException("DB 저장 실패"))
                .when(leaveService)
                .grantRegularAnnualLeave(10L, TODAY);

        batchService.run(TODAY);

        verify(leaveService).grantRegularAnnualLeave(
                10L,
                TODAY
        );

        verify(leaveService).grantRegularAnnualLeave(
                20L,
                TODAY
        );
    }
}
