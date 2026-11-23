
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyLeaveLateReviewServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate HIRE_DATE =
            LocalDate.of(2025, 9, 1);

    private static final LocalDate REVIEW_DATE =
            LocalDate.of(2026, 9, 5);

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private LeaveGrantRepository leaveGrantRepository;

    @Mock
    private MonthlyAttendanceService monthlyAttendanceService;

    @InjectMocks
    private MonthlyLeaveLateReviewService service;

    @BeforeEach
    void setUp() {

        Employee employee = mock(Employee.class);
        Company company = mock(Company.class);

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(employee));

        when(employee.getHireDate())
                .thenReturn(HIRE_DATE);

        // 회사 정보가 필요한 테스트에서만 아래를 사용한다.
        // Mockito Strict Stubbing 유지
        // 회사 조회는 각 테스트에 필요한 경우에 설정
    }

    private void givenCompany() {

        Employee employee = employeeRepository
                .findById(EMPLOYEE_ID)
                .orElseThrow();

        Company company = mock(Company.class);

        when(employee.getCompany()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);
    }

    @Test
    void 일주년이후_11개월분의_발생상태를_재검사한다() {

        givenCompany();

        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(EMPLOYEE_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenAnswer(invocation -> {

            LocalDate start = invocation.getArgument(2);

            if (start.equals(HIRE_DATE)) {
                return MonthlyAttendanceResult.PERFECT;
            }

            if (start.equals(HIRE_DATE.plusMonths(1))) {
                return MonthlyAttendanceResult.UNDETERMINED;
            }

            return MonthlyAttendanceResult.NOT_PERFECT;
        });

        List<MonthlyLeaveLateReviewService.ReviewResult> results =
                service.review(EMPLOYEE_ID, REVIEW_DATE);

        assertEquals(11, results.size());

        assertEquals(
                MonthlyLeaveLateReviewService.ReviewStatus.ELIGIBLE_FOR_REVIEW,
                results.get(0).status()
        );

        assertEquals(
                MonthlyLeaveLateReviewService.ReviewStatus.UNDETERMINED,
                results.get(1).status()
        );

        assertEquals(
                MonthlyLeaveLateReviewService.ReviewStatus.NOT_PERFECT,
                results.get(2).status()
        );

        assertEquals(
                LocalDate.of(2025, 10, 1),
                results.get(0).grantDate()
        );

        assertEquals(
                LocalDate.of(2026, 8, 31),
                results.get(0).expirationDate()
        );
    }

    @Test
    void 이미_발생한_연차는_재검사에서_중복대상으로_분류하지않는다() {

        givenCompany();

        LocalDate firstGrantDate =
                HIRE_DATE.plusMonths(1);

        when(leaveGrantRepository
                .existsByEmployeeIdAndGrantTypeAndGrantDate(
                        EMPLOYEE_ID,
                        "ANNUAL_MONTHLY",
                        firstGrantDate
                )).thenReturn(true);

        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(EMPLOYEE_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(MonthlyAttendanceResult.NOT_PERFECT);

        var results = service.review(
                EMPLOYEE_ID,
                REVIEW_DATE
        );

        assertEquals(
                MonthlyLeaveLateReviewService.ReviewStatus.ALREADY_GRANTED,
                results.get(0).status()
        );

        assertEquals(11, results.size());

        // 이미 이력이 있는 첫 달은 다시 평가하지 않는다.
        verify(monthlyAttendanceService, times(10))
                .evaluate(
                        eq(COMPANY_ID),
                        eq(EMPLOYEE_ID),
                        any(LocalDate.class),
                        any(LocalDate.class)
                );
    }

    @Test
    void 일주년이전에는_사후재검사를_허용하지않는다() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.review(
                        EMPLOYEE_ID,
                        LocalDate.of(2026, 8, 31)
                )
        );

        verifyNoInteractions(
                leaveGrantRepository,
                monthlyAttendanceService
        );
    }

    @Test
    void 결재가_나중에_확정되면_재검사결과도_변한다() {

        givenCompany();

        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(EMPLOYEE_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(MonthlyAttendanceResult.UNDETERMINED);

        var before = service.review(
                EMPLOYEE_ID,
                REVIEW_DATE
        );

        assertEquals(
                MonthlyLeaveLateReviewService.ReviewStatus.UNDETERMINED,
                before.get(0).status()
        );

        // 승인된 휴가가 근태 평가에 반영된 상황
        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(EMPLOYEE_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(MonthlyAttendanceResult.PERFECT);

        var after = service.review(
                EMPLOYEE_ID,
                REVIEW_DATE
        );

        assertEquals(
                MonthlyLeaveLateReviewService.ReviewStatus.ELIGIBLE_FOR_REVIEW,
                after.get(0).status()
        );
    }
}
