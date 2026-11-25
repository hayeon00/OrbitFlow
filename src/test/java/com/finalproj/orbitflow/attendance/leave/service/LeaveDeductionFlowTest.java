
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.entity.Document;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveDeductionFlowTest {

    private static final Long DOCUMENT_ID = 100L;
    private static final Long RECORD_ID = 200L;
    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;
    private static final Long LEAVE_TYPE_ID = 2L;

    private static final BigDecimal HALF_DAY =
            new BigDecimal("0.5");

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
    private MonthlyAttendanceService monthlyAttendanceService;

    @Mock
    private LeaveUsageAllocationService leaveUsageAllocationService;

    @InjectMocks
    private LeaveService leaveService;

    private Employee employee;
    private Company company;
    private Document document;
    private AttendanceRecord record;
    private LeaveType leaveType;

    @BeforeEach
    void setUp() {

        employee = mock(Employee.class);
        company = mock(Company.class);
        document = mock(Document.class);
        record = mock(AttendanceRecord.class);

        leaveType = LeaveType.builder()
                .id(LEAVE_TYPE_ID)
                .typeName("오전반차")
                .isCountable(true)
                .unitDays(HALF_DAY)
                .build();
    }

    private void prepareApprovedHalfDay() {

        when(document.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(document.getId())
                .thenReturn(DOCUMENT_ID);

        when(attendanceRecordRepository
                .findBySourceDocument_Id(DOCUMENT_ID))
                .thenReturn(Optional.of(record));

        when(record.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(record.getEmployee())
                .thenReturn(employee);

        when(record.getCompany())
                .thenReturn(company);

        // 일부 테스트가 불일치 상황을 만들기 위해 다시 덮어쓰므로 lenient
        lenient().when(record.getDays())
                .thenReturn(HALF_DAY);

        lenient().when(record.getLeaveType())
                .thenReturn(leaveType);

        // 차감 성공 경로에서만 사용된다.
        lenient().when(record.getId())
                .thenReturn(RECORD_ID);

        when(employee.getId())
                .thenReturn(EMPLOYEE_ID);

        when(employee.getCompany())
                .thenReturn(company);

        when(company.getId())
                .thenReturn(COMPANY_ID);
    }

    @Test
    void 승인된_오전반차는_연차배분서비스에_차감을_요청한다() {

        prepareApprovedHalfDay();

        leaveService.deduction(
                employee,
                HALF_DAY,
                document,
                leaveType
        );

        verify(
                leaveUsageAllocationService,
                times(1)
        ).allocateApprovedLeave(RECORD_ID);
    }

    @Test
    void 문서가_최종승인되지_않았으면_차감하지_않는다() {

        when(document.getStatus())
                .thenReturn(DocumentStatus.IN_PROGRESS);

        assertThrows(
                IllegalStateException.class,
                () -> leaveService.deduction(
                        employee,
                        HALF_DAY,
                        document,
                        leaveType
                )
        );

        verifyNoInteractions(
                leaveUsageAllocationService
        );
    }

    @Test
    void 근태기록이_승인되지_않았으면_차감하지_않는다() {

        when(document.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(document.getId())
                .thenReturn(DOCUMENT_ID);

        when(attendanceRecordRepository
                .findBySourceDocument_Id(DOCUMENT_ID))
                .thenReturn(Optional.of(record));

        when(record.getStatus())
                .thenReturn(DocumentStatus.IN_PROGRESS);

        assertThrows(
                IllegalStateException.class,
                () -> leaveService.deduction(
                        employee,
                        HALF_DAY,
                        document,
                        leaveType
                )
        );

        verifyNoInteractions(
                leaveUsageAllocationService
        );
    }

    @Test
    void 근태기록의_차감일수가_다르면_차감하지_않는다() {

        prepareApprovedHalfDay();

        when(record.getDays())
                .thenReturn(BigDecimal.ONE);

        assertThrows(
                IllegalStateException.class,
                () -> leaveService.deduction(
                        employee,
                        HALF_DAY,
                        document,
                        leaveType
                )
        );

        verifyNoInteractions(
                leaveUsageAllocationService
        );
    }

    @Test
    void 근태기록의_휴가유형이_다르면_차감하지_않는다() {

        prepareApprovedHalfDay();

        LeaveType differentType = LeaveType.builder()
                .id(3L)
                .typeName("오후반차")
                .isCountable(true)
                .unitDays(HALF_DAY)
                .build();

        when(record.getLeaveType())
                .thenReturn(differentType);

        assertThrows(
                IllegalStateException.class,
                () -> leaveService.deduction(
                        employee,
                        HALF_DAY,
                        document,
                        leaveType
                )
        );

        verifyNoInteractions(
                leaveUsageAllocationService
        );
    }

    @Test
    void 차감대상이_아닌_휴가는_연차를_차감하지_않는다() {

        LeaveType nonCountableType = LeaveType.builder()
                .id(99L)
                .typeName("비차감휴가")
                .isCountable(false)
                .unitDays(BigDecimal.ONE)
                .build();

        leaveService.deduction(
                employee,
                BigDecimal.ONE,
                document,
                nonCountableType
        );

        verifyNoInteractions(
                leaveUsageAllocationService
        );
    }
}
