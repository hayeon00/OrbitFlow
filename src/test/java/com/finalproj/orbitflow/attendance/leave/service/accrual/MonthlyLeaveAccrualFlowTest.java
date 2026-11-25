
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.attendance.leave.service.DailyAttendanceClassifier;
import com.finalproj.orbitflow.attendance.leave.service.MonthlyAttendanceService;
import com.finalproj.orbitflow.attendance.leave.service.LeaveTimePolicyService;
import com.finalproj.orbitflow.attendance.leave.service.LeaveUsageAllocationService;

import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
import com.finalproj.orbitflow.global.time.calendar.entity.CalendarDay;
import com.finalproj.orbitflow.global.time.calendar.enums.CalendarDayType;
import com.finalproj.orbitflow.global.time.calendar.repository.CalendarDayRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyLeaveAccrualFlowTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate HIRE_DATE =
            LocalDate.of(2026, 9, 1);

    private static final LocalDate PERIOD_END =
            LocalDate.of(2026, 9, 30);

    private static final LocalDate GRANT_DATE =
            LocalDate.of(2026, 10, 1);

    private static final LocalDate TARGET_DATE =
            LocalDate.of(2026, 9, 14);

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private LeaveGrantRepository leaveGrantRepository;

    @Mock
    private LeaveBalanceRepository leaveBalanceRepository;

    @Mock
    private CalendarDayRepository calendarDayRepository;

    @Mock
    private CommuteRepository commuteRepository;

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @Mock
    private AttendanceEventRepository attendanceEventRepository;

    @Mock
    private EffectiveAttendanceRuleService effectiveAttendanceRuleService;

    @Mock
    private LeaveTimePolicyService leaveTimePolicyService;

    @Mock
    private LeaveUsageAllocationService leaveUsageAllocationService;

    // 실제 일자별 판정기를 Mock 저장소와 연결한다.
    @InjectMocks
    private DailyAttendanceClassifier classifier;

    private MonthlyAttendanceService monthlyAttendanceService;

    @InjectMocks
    private MonthlyLeaveAccrualService leaveService;

    @BeforeEach
    void setUp() {

        // 두 실제 서비스를 연결한다.
        // MonthlyAttendanceService 자체는 Mock이 아니다.
        monthlyAttendanceService =
                new MonthlyAttendanceService(classifier);

        ReflectionTestUtils.setField(
                leaveService,
                "monthlyAttendanceService",
                monthlyAttendanceService
        );

        // 실제 저장 로직(LeaveGrantWriter)도 Mock 저장소와 연결한다.
        ReflectionTestUtils.setField(
                leaveService,
                "leaveGrantWriter",
                new LeaveGrantWriter(
                        leaveGrantRepository,
                        leaveBalanceRepository
                )
        );

        Employee employee = mock(Employee.class);
        Company company = mock(Company.class);

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(employee));

        when(employee.getStatus())
                .thenReturn(EmployeeStatus.ACTIVE);

        when(employee.getHireDate())
                .thenReturn(HIRE_DATE);

        when(employee.getCompany())
                .thenReturn(company);

        when(company.getId())
                .thenReturn(COMPANY_ID);

        // 달력은 9월 전체 날짜를 제공한다.
        when(calendarDayRepository
                .findByDateBetweenOrderByDateAsc(
                        HIRE_DATE,
                        PERIOD_END
                ))
                .thenReturn(calendarDays());

        // 이번 사례에는 승인된 휴가·출장·외근이 없다.
        when(attendanceRecordRepository
                .findOverlappingRecords(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DocumentStatus.APPROVED,
                        HIRE_DATE,
                        PERIOD_END
                ))
                .thenReturn(List.of());

        when(attendanceEventRepository
                .findApprovedOverlappingWorkEvents(
                        eq(COMPANY_ID),
                        eq(EMPLOYEE_ID),
                        eq(List.of(
                                BaseRole.BUSINESS_TRIP,
                                BaseRole.OUTWORK
                        )),
                        eq(DocumentStatus.APPROVED),
                        eq(HIRE_DATE),
                        eq(PERIOD_END)
                ))
                .thenReturn(List.of());

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.IN_PROGRESS,
                HIRE_DATE,
                PERIOD_END
        )).thenReturn(List.of());
    }

    private List<CalendarDay> calendarDays() {
        List<CalendarDay> days = new ArrayList<>();

        for (LocalDate date = HIRE_DATE;
             !date.isAfter(PERIOD_END);
             date = date.plusDays(1)) {

            days.add(new CalendarDay(
                    date,
                    date.getDayOfWeek().getValue(),
                    CalendarDayType.WORKDAY,
                    null
            ));
        }

        return days;
    }

    private List<Attendance> attendanceRecords(
            AttendanceStatus targetStatus,
            boolean omitTargetDate
    ) {
        List<Attendance> records = new ArrayList<>();

        for (LocalDate date = HIRE_DATE;
             !date.isAfter(PERIOD_END);
             date = date.plusDays(1)) {

            int dayOfWeek = date.getDayOfWeek().getValue();

            if (dayOfWeek > 5) {
                continue;
            }

            if (omitTargetDate && date.equals(TARGET_DATE)) {
                continue;
            }

            AttendanceStatus status =
                    date.equals(TARGET_DATE)
                            ? targetStatus
                            : AttendanceStatus.ON_TIME;

            boolean hasCheckIn =
                    status == AttendanceStatus.ON_TIME
                            || status == AttendanceStatus.LATE;

            records.add(
                    Attendance.builder()
                            .companyId(COMPANY_ID)
                            .employeeId(EMPLOYEE_ID)
                            .workDate(date)
                            .status(status)
                            .commuteAt(
                                    hasCheckIn
                                            ? date.atTime(
                                            status == AttendanceStatus.LATE
                                            ? 10 : 9,
                                            0
                                    )
                                            : null
                            )
                            .leaveAt(
                                    hasCheckIn
                                            ? date.atTime(18, 0)
                                            : null
                            )
                            .isCorrected(false)
                            .build()
            );
        }

        return records;
    }

    private void givenAttendance(
            AttendanceStatus targetStatus,
            boolean omitTargetDate
    ) {
        when(commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        HIRE_DATE,
                        PERIOD_END
                ))
                .thenReturn(
                        attendanceRecords(
                                targetStatus,
                                omitTargetDate
                        )
                );
    }

    private void executeGrant() {
        leaveService.grantMonthlyLeave(
                EMPLOYEE_ID,
                GRANT_DATE
        );
    }

    private void verifyOneDayGranted() {

        verify(leaveGrantRepository)
                .saveAndFlush(any(LeaveGrant.class));

        verify(leaveBalanceRepository)
                .addGrantedDaysAtomically(
                        eq(COMPANY_ID),
                        eq(EMPLOYEE_ID),
                        eq(2026),
                        eq(BigDecimal.ONE)
                );
    }

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
    void 한달간_모든근무일에_출근하면_연차1일을_부여한다() {

        givenAttendance(
                AttendanceStatus.ON_TIME,
                false
        );

        executeGrant();

        verifyOneDayGranted();
    }

    @Test
    void 실제_결근일이_있으면_연차를_부여하지_않는다() {

        givenAttendance(
                AttendanceStatus.ABSENT,
                false
        );

        executeGrant();

        verifyNotGranted();
    }

    @Test
    void 출근기록이_누락되면_연차부여를_보류한다() {

        givenAttendance(
                AttendanceStatus.ON_TIME,
                true
        );

        executeGrant();

        verifyNotGranted();
    }

    @Test
    void 지각했어도_실제출근했다면_연차1일을_부여한다() {

        givenAttendance(
                AttendanceStatus.LATE,
                false
        );

        executeGrant();

        verifyOneDayGranted();
    }


    @Test
    void 승인대기_휴가는_개근판정을_보류하고_최종승인후_개근으로_재평가한다() {

        // 1. 해당 날짜의 휴가 결재가 아직 진행 중이다.
        AttendanceRecord pendingLeave =
                mock(AttendanceRecord.class);

        when(pendingLeave.getStartDate())
                .thenReturn(TARGET_DATE);

        when(pendingLeave.getEndDate())
                .thenReturn(TARGET_DATE);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.IN_PROGRESS,
                HIRE_DATE,
                PERIOD_END
        )).thenReturn(List.of(pendingLeave));

        // 승인 대기 중이며 해당 날짜는 결근으로 기록된 상황
        givenAttendance(
                AttendanceStatus.ABSENT,
                false
        );

        MonthlyAttendanceResult pendingResult =
                monthlyAttendanceService.evaluate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        HIRE_DATE,
                        PERIOD_END
                );

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                pendingResult
        );

        // 2. 결재가 최종 승인되었다고 가정한다.
        // 이제 IN_PROGRESS 조회에서는 해당 휴가가 빠진다.
        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.IN_PROGRESS,
                HIRE_DATE,
                PERIOD_END
        )).thenReturn(List.of());

        AttendanceRecord approvedLeave =
                mock(AttendanceRecord.class);

        LeaveType leaveType =
                mock(LeaveType.class);

        when(approvedLeave.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(approvedLeave.getStartDate())
                .thenReturn(TARGET_DATE);

        when(approvedLeave.getEndDate())
                .thenReturn(TARGET_DATE);

        when(approvedLeave.getLeaveType())
                .thenReturn(leaveType);

        when(leaveType.getIsCountable())
                .thenReturn(true);

        when(leaveType.getAttendancePolicy())
                .thenReturn(LeaveAttendancePolicy.ATTENDANCE_CREDIT);

        when(leaveType.getUnitDays())
                .thenReturn(BigDecimal.ONE);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                HIRE_DATE,
                PERIOD_END
        )).thenReturn(List.of(approvedLeave));

        // 승인된 휴가가 적용된 날짜에는
        // 기존 결근 상태와 충돌하지 않도록
        // 근태 기록이 휴가 상태로 정합하게 반영된 상황을 구성한다.
        givenAttendance(
                AttendanceStatus.ON_TIME,
                true
        );

        MonthlyAttendanceResult approvedResult =
                monthlyAttendanceService.evaluate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        HIRE_DATE,
                        PERIOD_END
                );

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                approvedResult
        );

// 최종 승인 후 실제 연차 부여 서비스까지 실행
        executeGrant();

// 승인된 휴가를 개근으로 인정해
// 월별 연차 1일 지급 요청까지 이어지는지 검증
        verifyOneDayGranted();
    }


    @Test
    void 최종승인된_종일연차는_기존_결근기록이_있어도_개근으로_인정한다() {

        // 9월 14일에는 기존 ABSENT 기록이 남아 있다.
        givenAttendance(
                AttendanceStatus.ABSENT,
                false
        );

        AttendanceRecord approvedLeave =
                mock(AttendanceRecord.class);

        LeaveType leaveType =
                mock(LeaveType.class);

        when(approvedLeave.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(approvedLeave.getStartDate())
                .thenReturn(TARGET_DATE);

        when(approvedLeave.getEndDate())
                .thenReturn(TARGET_DATE);

        when(approvedLeave.getLeaveType())
                .thenReturn(leaveType);

        when(leaveType.getIsCountable())
                .thenReturn(true);

        when(leaveType.getAttendancePolicy())
                .thenReturn(LeaveAttendancePolicy.ATTENDANCE_CREDIT);

        when(leaveType.getUnitDays())
                .thenReturn(BigDecimal.ONE);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                HIRE_DATE,
                PERIOD_END
        )).thenReturn(List.of(approvedLeave));

        MonthlyAttendanceResult result =
                monthlyAttendanceService.evaluate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        HIRE_DATE,
                        PERIOD_END
                );

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                result
        );

        // 개근 판정이 실제 월별 연차 1일 지급까지 이어져야 한다.
        executeGrant();

        verifyOneDayGranted();
    }



    @Test
    void 허용된_경조휴가는_최종승인후_개근으로_인정한다() {

        ReflectionTestUtils.setField(
                classifier,
                "reviewCreditTypes",
                "경조휴가,보상휴가"
        );

        // 승인 이전에 남아 있던 결근 기록
        givenAttendance(AttendanceStatus.ABSENT, false);

        AttendanceRecord record = mock(AttendanceRecord.class);
        LeaveType type = mock(LeaveType.class);

        when(record.getStatus())
                .thenReturn(DocumentStatus.APPROVED);
        when(record.getStartDate())
                .thenReturn(TARGET_DATE);
        when(record.getEndDate())
                .thenReturn(TARGET_DATE);
        when(record.getLeaveType())
                .thenReturn(type);

        when(type.getAttendancePolicy())
                .thenReturn(LeaveAttendancePolicy.REVIEW_REQUIRED);
        when(type.getUnitDays())
                .thenReturn(BigDecimal.ONE);
        when(type.getTypeName())
                .thenReturn("경조휴가");

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                HIRE_DATE,
                PERIOD_END
        )).thenReturn(List.of(record));

        // 실제 월별 개근 평가
        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                monthlyAttendanceService.evaluate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        HIRE_DATE,
                        PERIOD_END
                )
        );

        // 승인된 경조휴가가 실제 연차 부여로 이어지는지 확인
        executeGrant();
        verifyOneDayGranted();
    }



}
