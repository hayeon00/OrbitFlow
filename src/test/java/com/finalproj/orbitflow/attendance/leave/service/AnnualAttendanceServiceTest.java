
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.attendance.leave.service.accrual.AnnualLeaveAccrualPolicy;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;
import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
import com.finalproj.orbitflow.global.time.calendar.entity.CalendarDay;
import com.finalproj.orbitflow.global.time.calendar.enums.CalendarDayType;
import com.finalproj.orbitflow.global.time.calendar.repository.CalendarDayRepository;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnualAttendanceServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate START =
            LocalDate.of(2026, 10, 5);

    private static final LocalDate END =
            LocalDate.of(2026, 10, 9);

    private static final LocalDate TARGET =
            LocalDate.of(2026, 10, 7);

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

    // 실제 일자별 판정기를 Mock 저장소와 연결한다.
    @InjectMocks
    private DailyAttendanceClassifier classifier;

    private AnnualAttendanceService service;

    @BeforeEach
    void setUp() {
        service = new AnnualAttendanceService(classifier);

        List<CalendarDay> days = new ArrayList<>();

        for (LocalDate date = START;
             !date.isAfter(END);
             date = date.plusDays(1)) {

            days.add(new CalendarDay(
                    date,
                    date.getDayOfWeek().getValue(),
                    CalendarDayType.WORKDAY,
                    null
            ));
        }

        when(calendarDayRepository
                .findByDateBetweenOrderByDateAsc(START, END))
                .thenReturn(days);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of());

        when(attendanceEventRepository
                .findApprovedOverlappingWorkEvents(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        List.of(
                                BaseRole.BUSINESS_TRIP,
                                BaseRole.OUTWORK
                        ),
                        DocumentStatus.APPROVED,
                        START,
                        END
                ))
                .thenReturn(List.of());

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.IN_PROGRESS,
                START,
                END
        )).thenReturn(List.of());
    }



    private Attendance attendance(
            LocalDate date,
            AttendanceStatus status,
            boolean hasCheckIn
    ) {
        return Attendance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .workDate(date)
                .status(status)
                .commuteAt(
                        hasCheckIn ? date.atTime(9, 0) : null
                )
                .leaveAt(
                        hasCheckIn ? date.atTime(18, 0) : null
                )
                .isCorrected(false)
                .build();
    }

    private List<Attendance> fiveDays(
            AttendanceStatus targetStatus,
            boolean omitTarget
    ) {
        List<Attendance> records = new ArrayList<>();

        for (LocalDate date = START;
             !date.isAfter(END);
             date = date.plusDays(1)) {

            if (omitTarget && date.equals(TARGET)) {
                continue;
            }

            AttendanceStatus status =
                    date.equals(TARGET)
                            ? targetStatus
                            : AttendanceStatus.ON_TIME;

            boolean hasCheckIn =
                    status == AttendanceStatus.ON_TIME
                            || status == AttendanceStatus.LATE;

            records.add(
                    attendance(date, status, hasCheckIn)
            );
        }

        return records;
    }

    private void givenAttendance(
            AttendanceStatus targetStatus,
            boolean omitTarget
    ) {
        when(commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                        COMPANY_ID, EMPLOYEE_ID, START, END
                ))
                .thenReturn(fiveDays(targetStatus, omitTarget));
    }

    private AnnualAttendanceService.AnnualAttendanceSummary evaluate() {
        return service.evaluate(
                COMPANY_ID, EMPLOYEE_ID, START, END
        );
    }


    private AttendanceRecord approvedLeave(
            LeaveAttendancePolicy policy
    ) {
        AttendanceRecord record =
                mock(AttendanceRecord.class);

        LeaveType type =
                mock(LeaveType.class);

        when(record.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(record.getStartDate())
                .thenReturn(TARGET);

        when(record.getEndDate())
                .thenReturn(TARGET);

        when(record.getLeaveType())
                .thenReturn(type);

        when(type.getAttendancePolicy())
                .thenReturn(policy);

        // 종일 연차 인정 정책을 검사하는 경우에만
        // 실제로 필요한 추가 속성을 설정한다.
        if (policy == LeaveAttendancePolicy.ATTENDANCE_CREDIT) {

            when(type.getIsCountable())
                    .thenReturn(true);

            when(type.getUnitDays())
                    .thenReturn(BigDecimal.ONE);
        }

        return record;
    }


    @Test
    void 모든_근무일에_출근하면_5일_모두_인정() {
        givenAttendance(AttendanceStatus.ON_TIME, false);

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(5, result.attendedDays());
        assertEquals(0, result.undeterminedDays());
    }

    @Test
    void 하루_결근하면_출근인정일은_4일() {
        givenAttendance(AttendanceStatus.ABSENT, false);

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(0, result.undeterminedDays());
    }

    @Test
    void 지각해도_출근한_날로_인정() {
        givenAttendance(AttendanceStatus.LATE, false);

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(5, result.attendedDays());
    }

    @Test
    void 기록이_누락된_날은_미확정() {
        givenAttendance(AttendanceStatus.ON_TIME, true);

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(1, result.undeterminedDays());
    }

    @Test
    void 승인된_종일휴가는_출근으로_인정() {
        List<Attendance> records =
                fiveDays(AttendanceStatus.ON_TIME, true);

        when(commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                        COMPANY_ID, EMPLOYEE_ID, START, END
                ))
                .thenReturn(records);

        AttendanceRecord approvedRecord =
                approvedLeave(LeaveAttendancePolicy.ATTENDANCE_CREDIT);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        var result = evaluate();

        assertEquals(5, result.attendedDays());
        assertEquals(0, result.undeterminedDays());
    }

    @Test
    void 승인된_오전반차는_반차_시간판정을_충족하면_출근으로_인정() {
        // 09:00 출근 ~ 18:00 퇴근
        givenAttendance(AttendanceStatus.LATE, false);

        AttendanceRecord approvedRecord =
                approvedLeave(LeaveAttendancePolicy.PARTIAL_DAY);

        LeaveType type = approvedRecord.getLeaveType();

        when(type.getTypeName()).thenReturn("오전반차");
        when(type.getUnitDays()).thenReturn(new BigDecimal("0.5"));

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID, EMPLOYEE_ID, TARGET
        )).thenReturn(new EffectiveAttendanceRule(
                COMPANY_ID, EMPLOYEE_ID, TARGET,
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0),
                java.time.LocalTime.of(12, 0), java.time.LocalTime.of(13, 0),
                60, false, 100L
        ));

        LeaveTimePolicy policy = mock(LeaveTimePolicy.class);
        when(policy.getMorningHalfReturnTime())
                .thenReturn(java.time.LocalTime.of(14, 0));
        when(leaveTimePolicyService.getPolicy(COMPANY_ID))
                .thenReturn(policy);

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(5, result.attendedDays());
        assertEquals(0, result.undeterminedDays());
    }

    @Test
    void 승인된_출장일에_일반출근기록이_있으면_미확정() {
        // 출장·외근일에는 출근 체크가 막혀 있으므로 데이터 불일치로 본다.
        givenAttendance(AttendanceStatus.ON_TIME, false);

        AttendanceEvent event = mock(AttendanceEvent.class);
        when(event.getStartDate()).thenReturn(TARGET);
        when(event.getEndDate()).thenReturn(TARGET);

        when(attendanceEventRepository
                .findApprovedOverlappingWorkEvents(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        List.of(BaseRole.BUSINESS_TRIP, BaseRole.OUTWORK),
                        DocumentStatus.APPROVED,
                        START,
                        END
                ))
                .thenReturn(List.of(event));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(1, result.undeterminedDays());
    }

    @Test
    void 출근율_80퍼센트_경계를_정확하게_계산() {
        givenAttendance(AttendanceStatus.ABSENT, false);

        var summary = evaluate();

        AnnualLeaveAccrualPolicy policy =
                new AnnualLeaveAccrualPolicy();

        var decision = policy.evaluate(
                LocalDate.of(2025, 10, 10),
                LocalDate.of(2026, 10, 10),
                summary.scheduledDays(),
                summary.attendedDays(),
                summary.undeterminedDays()
        );

        assertEquals(5, summary.scheduledDays());
        assertEquals(4, summary.attendedDays());
        assertEquals(
                AnnualLeaveAccrualPolicy.Status.ELIGIBLE,
                decision.status()
        );
        assertEquals(15, decision.grantDays());
    }


    @Test
    void 승인된_병가처럼_별도검토가_필요한_휴가는_미확정으로_처리한다() {

        givenAttendance(AttendanceStatus.ON_TIME, true);

        AttendanceRecord approvedRecord =
                approvedLeave(LeaveAttendancePolicy.REVIEW_REQUIRED);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(1, result.undeterminedDays());
    }

    @Test
    void 소정근로일수_제외정책은_비례산정_구현전까지_판정을_보류한다() {

        givenAttendance(AttendanceStatus.ON_TIME, true);

        AttendanceRecord approvedRecord =
                approvedLeave(LeaveAttendancePolicy.EXCLUDED);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        var result = evaluate();

        // 현재는 EXCLUDED를 실제 소정근로일수에서 제외하지 않고,
        // 별도 산정이 필요하므로 미확정으로 보류한다.
        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(1, result.undeterminedDays());
    }


    @Test
    void 승인대기_휴가는_연간출근율에서_미확정으로_분류한다() {

        givenAttendance(
                AttendanceStatus.ABSENT,
                false
        );

        AttendanceRecord pendingLeave =
                mock(AttendanceRecord.class);

        when(pendingLeave.getStartDate())
                .thenReturn(TARGET);

        when(pendingLeave.getEndDate())
                .thenReturn(TARGET);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.IN_PROGRESS,
                START,
                END
        )).thenReturn(List.of(pendingLeave));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(1, result.undeterminedDays());
    }

    @Test
    void 최종승인된_휴가는_연간출근율에서_출근으로_재평가한다() {

        // 결재 진행 중인 기록은 없어야 한다.
        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.IN_PROGRESS,
                START,
                END
        )).thenReturn(List.of());

        // 승인된 휴가 사용일에는 출퇴근 기록이 없는 상황
        givenAttendance(
                AttendanceStatus.ON_TIME,
                true
        );

        AttendanceRecord approvedRecord =
                approvedLeave(
                        LeaveAttendancePolicy.ATTENDANCE_CREDIT
                );

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(5, result.attendedDays());
        assertEquals(0, result.undeterminedDays());
    }


    @Test
    void 최종승인된_종일연차는_기존_결근기록이_있어도_출근일수에_포함한다() {

        // 5일 중 하루에 ABSENT 기록이 남아 있는 상황
        givenAttendance(
                AttendanceStatus.ABSENT,
                false
        );

        // 해당 날짜의 종일 휴가는 최종 승인된 상태
        AttendanceRecord approvedRecord =
                approvedLeave(
                        LeaveAttendancePolicy.ATTENDANCE_CREDIT
                );

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());

        // 기존 ABSENT 기록이 있어도 승인 휴가로 출근 인정
        assertEquals(5, result.attendedDays());

        assertEquals(0, result.undeterminedDays());
    }


    @Test
    void 출근인정정책이어도_차감대상이_아니면_종일연차로_자동인정하지않는다() {

        // 해당 날짜에는 출퇴근 기록이 없다.
        givenAttendance(
                AttendanceStatus.ON_TIME,
                true
        );

        AttendanceRecord approvedRecord =
                mock(AttendanceRecord.class);

        LeaveType leaveType =
                mock(LeaveType.class);

        when(approvedRecord.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(approvedRecord.getStartDate())
                .thenReturn(TARGET);

        when(approvedRecord.getEndDate())
                .thenReturn(TARGET);

        when(approvedRecord.getLeaveType())
                .thenReturn(leaveType);

        // 기존 연간 평가에서는 이 값만 보고 인정했지만
        // 새 공통 규칙에서는 isCountable도 확인한다.
        when(leaveType.getAttendancePolicy())
                .thenReturn(LeaveAttendancePolicy.ATTENDANCE_CREDIT);

        when(leaveType.getIsCountable())
                .thenReturn(false);

        when(attendanceRecordRepository.findOverlappingRecords(
                COMPANY_ID,
                EMPLOYEE_ID,
                DocumentStatus.APPROVED,
                START,
                END
        )).thenReturn(List.of(approvedRecord));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(4, result.attendedDays());
        assertEquals(1, result.undeterminedDays());
    }


    @Test
    void 허용된_경조휴가는_연간출근율에_반영한다() {

        ReflectionTestUtils.setField(
                classifier,
                "reviewCreditTypes",
                "경조휴가,보상휴가"
        );

        givenAttendance(AttendanceStatus.ABSENT, false);

        AttendanceRecord record = mock(AttendanceRecord.class);
        LeaveType type = mock(LeaveType.class);

        when(record.getStatus())
                .thenReturn(DocumentStatus.APPROVED);
        when(record.getStartDate())
                .thenReturn(TARGET);
        when(record.getEndDate())
                .thenReturn(TARGET);
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
                START,
                END
        )).thenReturn(List.of(record));

        var result = evaluate();

        assertEquals(5, result.scheduledDays());
        assertEquals(5, result.attendedDays());
        assertEquals(0, result.undeterminedDays());
    }





}
