
package com.finalproj.orbitflow.attendance.leave.service;

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
import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.global.time.calendar.entity.CalendarDay;
import com.finalproj.orbitflow.global.time.calendar.enums.CalendarDayType;
import com.finalproj.orbitflow.global.time.calendar.repository.CalendarDayRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyAttendanceServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    // 월요일부터 금요일까지의 5일
    private static final LocalDate START =
            LocalDate.of(2026, 10, 5);

    private static final LocalDate END =
            LocalDate.of(2026, 10, 9);

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

    @InjectMocks
    private MonthlyAttendanceService service;




    private void prepareCalendarAndApprovedRecords() {
        prepareCalendarAndApprovedRecords(
                List.of(),
                List.of()
        );
    }

    private void prepareCalendarAndApprovedRecords(
            List<AttendanceEvent> workEvents
    ) {
        prepareCalendarAndApprovedRecords(
                workEvents,
                List.of()
        );
    }

    private void prepareCalendarAndApprovedRecords(
            List<AttendanceEvent> workEvents,
            List<AttendanceRecord> leaveRecords
    ) {
        when(calendarDayRepository
                .findByDateBetweenOrderByDateAsc(START, END))
                .thenReturn(workdays());

        when(attendanceRecordRepository
                .findOverlappingRecords(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        DocumentStatus.APPROVED,
                        START,
                        END
                ))
                .thenReturn(leaveRecords);

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
                .thenReturn(workEvents);
    }


    private AttendanceRecord fullDayLeave(
            DocumentStatus status
    ) {
        LeaveType leaveType = mock(LeaveType.class);

        when(leaveType.getIsCountable())
                .thenReturn(true);

        when(leaveType.getAttendancePolicy())
                .thenReturn(
                        LeaveAttendancePolicy.ATTENDANCE_CREDIT
                );

        when(leaveType.getUnitDays())
                .thenReturn(BigDecimal.ONE);

        AttendanceRecord record = mock(AttendanceRecord.class);

        when(record.getStatus()).thenReturn(status);

        if (status == DocumentStatus.APPROVED) {
            when(record.getStartDate())
                    .thenReturn(LocalDate.of(2026, 10, 7));

            when(record.getEndDate())
                    .thenReturn(LocalDate.of(2026, 10, 7));

            when(record.getLeaveType()).thenReturn(leaveType);
        }

        return record;
    }




    @Test
    void 모든_근무일에_정상출근하면_PERFECT() {

        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(7, AttendanceStatus.ON_TIME, true),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                evaluate()
        );
    }

    @Test
    void 지각이_있어도_출근한_경우_PERFECT() {

        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.LATE, true),
                attendance(7, AttendanceStatus.ON_TIME, true),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                evaluate()
        );
    }

    @Test
    void 실제_결근이_있으면_NOT_PERFECT() {

        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(7, AttendanceStatus.ABSENT, false),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.NOT_PERFECT,
                evaluate()
        );
    }

    @Test
    void 출근기록이_누락되면_UNDETERMINED() {

        prepareCalendarAndApprovedRecords();

        // 7일 출근 기록 없음
        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                evaluate()
        );
    }

    @Test
    void 근무예정_상태는_UNDETERMINED() {

        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(7, AttendanceStatus.BEFORE_WORK, false),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                evaluate()
        );
    }

    @Test
    void 출근상태인데_출근시각이_없으면_UNDETERMINED() {

        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(7, AttendanceStatus.ON_TIME, false),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                evaluate()
        );
    }


    @Test
    void 잘못된_평가기간은_예외() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.evaluate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        END,
                        START
                )
        );
    }

    private MonthlyAttendanceResult evaluate() {

        return service.evaluate(
                COMPANY_ID,
                EMPLOYEE_ID,
                START,
                END
        );
    }

    private void mockAttendance(List<Attendance> records) {

        when(commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                        eq(COMPANY_ID),
                        eq(EMPLOYEE_ID),
                        eq(START),
                        eq(END)
                ))
                .thenReturn(records);
    }

    private List<CalendarDay> workdays() {

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

        return days;
    }

    private Attendance attendance(
            int dayOfMonth,
            AttendanceStatus status,
            boolean hasCommuteAt
    ) {

        LocalDate date =
                LocalDate.of(2026, 10, dayOfMonth);

        return Attendance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .workDate(date)
                .status(status)
                .commuteAt(
                        hasCommuteAt
                                ? LocalDateTime.of(
                                2026, 10, dayOfMonth,
                                9, 0
                        )
                                : null
                )
                .isCorrected(false)
                .build();
    }


    @Test
    void 승인된_출장일은_출근기록이_없어도_개근_인정() {

        AttendanceEvent event = mock(AttendanceEvent.class);

        when(event.getStartDate())
                .thenReturn(LocalDate.of(2026, 10, 7));

        when(event.getActualEndDate())
                .thenReturn(null);

        when(event.getEndDate())
                .thenReturn(LocalDate.of(2026, 10, 7));

        prepareCalendarAndApprovedRecords(List.of(event));

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                // 7일은 출장으로 출퇴근 기록 없음
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                evaluate()
        );
    }

    @Test
    void 승인된_외근과_결근기록이_충돌하면_판정보류() {

        AttendanceEvent event = mock(AttendanceEvent.class);

        when(event.getStartDate())
                .thenReturn(LocalDate.of(2026, 10, 7));

        when(event.getActualEndDate())
                .thenReturn(null);

        when(event.getEndDate())
                .thenReturn(LocalDate.of(2026, 10, 7));

        prepareCalendarAndApprovedRecords(List.of(event));

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(7, AttendanceStatus.ABSENT, false),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                evaluate()
        );
    }


    @Test
    void 승인된_전일연차는_출근기록이_없어도_개근_인정() {

        prepareCalendarAndApprovedRecords(
                List.of(),
                List.of(fullDayLeave(DocumentStatus.APPROVED))
        );

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                // 7일: 승인된 전일 연차
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                evaluate()
        );
    }

    @Test
    void 미승인_연차는_출근으로_인정하지_않음() {

        // Repository는 APPROVED 상태만 조회하므로
        // 미승인 신청은 결과에 포함되지 않는다.
        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                // 7일: 출근 기록 없음, 승인된 연차 없음
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                evaluate()
        );
    }

    @Test
    void 승인된_전일연차와_결근기록이_충돌하면_보류() {

        prepareCalendarAndApprovedRecords(
                List.of(),
                List.of(fullDayLeave(DocumentStatus.APPROVED))
        );

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(7, AttendanceStatus.ABSENT, false),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.UNDETERMINED,
                evaluate()
        );
    }



    // ── 오전반차 / 오후반차 개근 판정 추가 테스트 ──

    private AttendanceRecord partialLeave(String typeName) {
        LeaveType type = mock(LeaveType.class);
        when(type.getAttendancePolicy()).thenReturn(LeaveAttendancePolicy.PARTIAL_DAY);
        when(type.getTypeName()).thenReturn(typeName);
        when(type.getUnitDays()).thenReturn(new BigDecimal("0.5"));

        AttendanceRecord record = mock(AttendanceRecord.class);
        when(record.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(record.getStartDate()).thenReturn(LocalDate.of(2026, 10, 7));
        when(record.getEndDate()).thenReturn(LocalDate.of(2026, 10, 7));
        when(record.getLeaveType()).thenReturn(type);
        return record;
    }

    private Attendance clocked(int day, AttendanceStatus status, int inHour,
                               int inMinute, int outHour, int outMinute) {
        return Attendance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .workDate(LocalDate.of(2026, 10, day))
                .status(status)
                .commuteAt(LocalDateTime.of(2026, 10, day, inHour, inMinute))
                .leaveAt(LocalDateTime.of(2026, 10, day, outHour, outMinute))
                .isCorrected(false)
                .build();
    }

    private void mockPartialAttendance(Attendance oct7) {
        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                oct7,
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));
    }

    private void mockPartialDayTimes(boolean morning) {
        EffectiveAttendanceRule rule = new EffectiveAttendanceRule(
                COMPANY_ID, EMPLOYEE_ID, LocalDate.of(2026, 10, 7),
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                LocalTime.of(12, 0), LocalTime.of(13, 0),
                60, false, 100L
        );
        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID, EMPLOYEE_ID, LocalDate.of(2026, 10, 7)
        )).thenReturn(rule);

        LeaveTimePolicy policy = mock(LeaveTimePolicy.class);
        if (morning) {
            when(policy.getMorningHalfReturnTime()).thenReturn(LocalTime.of(14, 0));
        } else {
            when(policy.getAfternoonHalfLeaveTime()).thenReturn(LocalTime.of(14, 0));
        }
        when(leaveTimePolicyService.getPolicy(COMPANY_ID)).thenReturn(policy);
    }

    @Test
    void 오전반차_복귀시간_이전_출근_정상퇴근이면_PERFECT() {
        prepareCalendarAndApprovedRecords(
                List.of(), List.of(partialLeave("오전반차"))
        );
        mockPartialAttendance(clocked(7, AttendanceStatus.ON_TIME, 13, 55, 18, 0));
        mockPartialDayTimes(true);

        assertEquals(MonthlyAttendanceResult.PERFECT, evaluate());
    }

    @Test
    void 오후반차_정상출근_반차퇴근시간_이후_퇴근이면_PERFECT() {
        prepareCalendarAndApprovedRecords(
                List.of(), List.of(partialLeave("오후반차"))
        );
        mockPartialAttendance(clocked(7, AttendanceStatus.ON_TIME, 9, 0, 14, 5));
        mockPartialDayTimes(false);

        assertEquals(MonthlyAttendanceResult.PERFECT, evaluate());
    }

    @Test
    void 오전반차_복귀시간보다_늦게_출근하면_UNDETERMINED() {
        prepareCalendarAndApprovedRecords(
                List.of(), List.of(partialLeave("오전반차"))
        );
        mockPartialAttendance(clocked(7, AttendanceStatus.LATE, 14, 20, 18, 0));
        mockPartialDayTimes(true);

        assertEquals(MonthlyAttendanceResult.UNDETERMINED, evaluate());
    }

    @Test
    void 오후반차_퇴근기준보다_일찍_퇴근하면_UNDETERMINED() {
        prepareCalendarAndApprovedRecords(
                List.of(), List.of(partialLeave("오후반차"))
        );
        mockPartialAttendance(clocked(7, AttendanceStatus.ON_TIME, 9, 0, 13, 30));
        mockPartialDayTimes(false);

        assertEquals(MonthlyAttendanceResult.UNDETERMINED, evaluate());
    }

    @Test
    void 오전반차_승인됐지만_출근기록이_없으면_UNDETERMINED() {
        prepareCalendarAndApprovedRecords(
                List.of(), List.of(partialLeave("오전반차"))
        );
        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(MonthlyAttendanceResult.UNDETERMINED, evaluate());
    }

    @Test
    void 오후반차_승인됐지만_퇴근기록이_없으면_UNDETERMINED() {
        prepareCalendarAndApprovedRecords(
                List.of(), List.of(partialLeave("오후반차"))
        );
        mockPartialAttendance(attendance(7, AttendanceStatus.ON_TIME, true));

        assertEquals(MonthlyAttendanceResult.UNDETERMINED, evaluate());
    }


    @Test
    void 직원별_10시19시_정상출퇴근은_PERFECT() {
        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                clocked(
                        7,
                        AttendanceStatus.ON_TIME,
                        10, 0,
                        19, 0
                ),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                evaluate()
        );
    }

    @Test
    void 직원별_10시규칙에서_10시1분_지각해도_출근했다면_PERFECT() {
        prepareCalendarAndApprovedRecords();

        mockAttendance(List.of(
                attendance(5, AttendanceStatus.ON_TIME, true),
                attendance(6, AttendanceStatus.ON_TIME, true),
                clocked(
                        7,
                        AttendanceStatus.LATE,
                        10, 1,
                        19, 0
                ),
                attendance(8, AttendanceStatus.ON_TIME, true),
                attendance(9, AttendanceStatus.ON_TIME, true)
        ));

        assertEquals(
                MonthlyAttendanceResult.PERFECT,
                evaluate()
        );
    }





}
