
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
import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
import com.finalproj.orbitflow.global.time.calendar.entity.CalendarDay;
import com.finalproj.orbitflow.global.time.calendar.enums.CalendarDayType;
import com.finalproj.orbitflow.global.time.calendar.repository.CalendarDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 연차 발생 평가용 일자별 근태 판정 (월별 개근·연간 출근율 공통).
 *
 * 기간의 근태 데이터를 한 번에 조회한 뒤 날짜마다 하나의 결과를 낸다.
 * 실제 출퇴근·결재 데이터는 수정하지 않는다.
 *
 * 판정 순서:
 * 1. 토·일요일, 공휴일·유급휴일 → NOT_SCHEDULED
 * 2. 달력 누락, 무급휴일 → UNDETERMINED
 * 3. 결재 진행 중인 휴가가 걸린 날 → UNDETERMINED
 * 4. 승인 휴가·출장·외근이 같은 날 2건 이상 겹침 → UNDETERMINED
 * 5. 승인 휴가 1건:
 *    - 출근 인정 종일 휴가(ApprovedFullDayLeavePolicy, ApprovedReviewLeavePolicy)
 *      → 출퇴근 기록 없음·VACATION·ABSENT면 ATTENDED, 실제 출근 기록과 겹치면 UNDETERMINED
 *    - 오전·오후 반차 → 실제 출퇴근 시각이 반차 정책 시간을 충족하면 ATTENDED, 아니면 UNDETERMINED
 *    - 그 밖의 휴가 → UNDETERMINED
 * 6. 승인 출장·외근 1건 → 출퇴근 기록 없음·BUSINESS_TRIP·OUTSIDE면 ATTENDED,
 *    그 외 기록(일반 출근·결근 등)과 겹치면 UNDETERMINED
 * 7. 일반 근태: ABSENT → ABSENT, 출근시각 있는 ON_TIME·LATE → ATTENDED, 나머지 → UNDETERMINED
 *
 * 애매한 경우 임의로 우선순위를 정하지 않고 UNDETERMINED로 보류한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyAttendanceClassifier {

    public enum DayResult {

        /** 소정근로일이 아님 (주말·공휴일·유급휴일) */
        NOT_SCHEDULED,

        /** 출근 또는 출근 인정 */
        ATTENDED,

        /** 결근 확정 */
        ABSENT,

        /** 데이터 누락·결재 대기·기록 충돌로 판정 보류 */
        UNDETERMINED
    }

    private static final List<BaseRole> WORK_EVENT_ROLES =
            List.of(BaseRole.BUSINESS_TRIP, BaseRole.OUTWORK);

    private final CalendarDayRepository calendarDayRepository;
    private final CommuteRepository commuteRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceEventRepository attendanceEventRepository;
    private final EffectiveAttendanceRuleService effectiveAttendanceRuleService;
    private final LeaveTimePolicyService leaveTimePolicyService;

    /** 회사 정책상 출근 인정하는 REVIEW_REQUIRED 휴가 이름 (쉼표 구분) */
    @Value("${orbitflow.leave.review-credit-types:}")
    private String reviewCreditTypes;

    /**
     * 기간의 날짜별 판정 결과 (날짜 오름차순).
     *
     * 기간 검증은 호출하는 평가 서비스의 책임이다.
     */
    public Map<LocalDate, DayResult> classify(
            Long companyId,
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        PeriodData data = load(companyId, employeeId, startDate, endDate);

        Map<LocalDate, DayResult> results = new LinkedHashMap<>();

        for (LocalDate date = startDate;
             !date.isAfter(endDate);
             date = date.plusDays(1)) {

            results.put(date, classifyDay(companyId, employeeId, date, data));
        }

        return results;
    }

    private record PeriodData(
            Map<LocalDate, CalendarDay> calendar,
            Map<LocalDate, Attendance> attendances,
            List<AttendanceRecord> approvedLeaves,
            List<AttendanceRecord> pendingLeaves,
            List<AttendanceEvent> workEvents
    ) {
    }

    private PeriodData load(
            Long companyId,
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<LocalDate, CalendarDay> calendar =
                calendarDayRepository
                        .findByDateBetweenOrderByDateAsc(startDate, endDate)
                        .stream()
                        .collect(Collectors.toMap(
                                CalendarDay::getDate,
                                Function.identity()
                        ));

        Map<LocalDate, Attendance> attendances =
                commuteRepository
                        .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                                companyId, employeeId, startDate, endDate
                        )
                        .stream()
                        .collect(Collectors.toMap(
                                Attendance::getWorkDate,
                                Function.identity()
                        ));

        List<AttendanceRecord> approvedLeaves =
                attendanceRecordRepository.findOverlappingRecords(
                        companyId,
                        employeeId,
                        DocumentStatus.APPROVED,
                        startDate,
                        endDate
                );

        List<AttendanceRecord> pendingLeaves =
                attendanceRecordRepository.findOverlappingRecords(
                        companyId,
                        employeeId,
                        DocumentStatus.IN_PROGRESS,
                        startDate,
                        endDate
                );

        List<AttendanceEvent> workEvents =
                attendanceEventRepository.findApprovedOverlappingWorkEvents(
                        companyId,
                        employeeId,
                        WORK_EVENT_ROLES,
                        DocumentStatus.APPROVED,
                        startDate,
                        endDate
                );

        return new PeriodData(
                calendar, attendances, approvedLeaves, pendingLeaves, workEvents
        );
    }

    private DayResult classifyDay(
            Long companyId,
            Long employeeId,
            LocalDate date,
            PeriodData data
    ) {
        // 1~2. 소정근로일 여부
        DayOfWeek dayOfWeek = date.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY
                || dayOfWeek == DayOfWeek.SUNDAY) {
            return DayResult.NOT_SCHEDULED;
        }

        CalendarDay calendarDay = data.calendar().get(date);

        // 달력 누락은 임의로 휴일로 처리하지 않는다.
        if (calendarDay == null) {
            return DayResult.UNDETERMINED;
        }

        CalendarDayType dayType = calendarDay.getDayType();

        if (dayType == CalendarDayType.PUBLIC_HOLIDAY
                || dayType == CalendarDayType.PAID_HOLIDAY) {
            return DayResult.NOT_SCHEDULED;
        }

        if (dayType != CalendarDayType.WORKDAY) {
            return DayResult.UNDETERMINED;
        }

        // 3. 결재 결과가 확정될 때까지 보류
        boolean hasPendingLeave = data.pendingLeaves().stream()
                .anyMatch(record -> coversDate(record, date));

        if (hasPendingLeave) {
            return DayResult.UNDETERMINED;
        }

        List<AttendanceRecord> leaves = data.approvedLeaves().stream()
                .filter(record -> record.getStatus() == DocumentStatus.APPROVED)
                .filter(record -> coversDate(record, date))
                .toList();

        List<AttendanceEvent> events = data.workEvents().stream()
                .filter(event -> coversDate(event, date))
                .toList();

        // 4. 승인 기록 중첩
        if (leaves.size() + events.size() > 1) {
            return DayResult.UNDETERMINED;
        }

        Attendance attendance = data.attendances().get(date);

        // 5. 승인 휴가
        if (!leaves.isEmpty()) {
            return classifyLeaveDay(
                    companyId, employeeId, date, attendance, leaves.get(0)
            );
        }

        // 6. 승인 출장·외근
        if (!events.isEmpty()) {
            if (attendance == null
                    || attendance.getStatus() == AttendanceStatus.BUSINESS_TRIP
                    || attendance.getStatus() == AttendanceStatus.OUTSIDE) {
                return DayResult.ATTENDED;
            }

            return DayResult.UNDETERMINED;
        }

        // 7. 일반 근태
        if (attendance == null) {
            return DayResult.UNDETERMINED;
        }

        AttendanceStatus status = attendance.getStatus();

        if (status == AttendanceStatus.ABSENT) {
            return DayResult.ABSENT;
        }

        if (status == AttendanceStatus.ON_TIME
                || status == AttendanceStatus.LATE) {
            return attendance.getCommuteAt() != null
                    ? DayResult.ATTENDED
                    : DayResult.UNDETERMINED;
        }

        return DayResult.UNDETERMINED;
    }

    private DayResult classifyLeaveDay(
            Long companyId,
            Long employeeId,
            LocalDate date,
            Attendance attendance,
            AttendanceRecord leave
    ) {
        LeaveType leaveType = leave.getLeaveType();

        if (leaveType == null || leaveType.getAttendancePolicy() == null) {
            return DayResult.UNDETERMINED;
        }

        // 출근 인정 종일 휴가:
        // 결근 처리 배치가 남긴 ABSENT 기록은 수정하지 않고,
        // 평가에서만 승인 휴가를 우선 적용한다.
        if (ApprovedFullDayLeavePolicy.isRecognized(leave, date)
                || ApprovedReviewLeavePolicy.isRecognized(
                leave, date, reviewCreditTypes)) {

            if (attendance == null
                    || attendance.getStatus() == AttendanceStatus.VACATION
                    || attendance.getStatus() == AttendanceStatus.ABSENT) {
                return DayResult.ATTENDED;
            }

            // 승인 휴가와 실제 출근 기록이 겹침
            return DayResult.UNDETERMINED;
        }

        if (leaveType.getAttendancePolicy()
                == LeaveAttendancePolicy.PARTIAL_DAY) {

            return isPartialDayFulfilled(
                    companyId, employeeId, date, attendance, leaveType
            )
                    ? DayResult.ATTENDED
                    : DayResult.UNDETERMINED;
        }

        return DayResult.UNDETERMINED;
    }

    /**
     * 오전반차 / 오후반차의 실제 출퇴근 기록 평가.
     *
     * 시간 기준 미충족은 곧바로 결근 확정하지 않고
     * 검토 대상(false → UNDETERMINED)으로 처리한다.
     */
    private boolean isPartialDayFulfilled(
            Long companyId,
            Long employeeId,
            LocalDate date,
            Attendance attendance,
            LeaveType leaveType
    ) {
        String typeName = leaveType.getTypeName();

        boolean morning = "오전반차".equals(typeName);
        boolean afternoon = "오후반차".equals(typeName);

        // 반반차 등 별도 시간 정책이 없는 휴가
        if (!morning && !afternoon) {
            return false;
        }

        if (leaveType.getUnitDays() == null
                || leaveType.getUnitDays()
                .compareTo(new BigDecimal("0.5")) != 0) {
            return false;
        }

        // 승인된 반차와 결근·VACATION 등 다른 상태가 충돌하면 자동 인정 불가
        if (attendance == null
                || (attendance.getStatus() != AttendanceStatus.ON_TIME
                && attendance.getStatus() != AttendanceStatus.LATE)) {
            return false;
        }

        LocalDateTime commuteAt = attendance.getCommuteAt();
        LocalDateTime leaveAt = attendance.getLeaveAt();

        if (commuteAt == null
                || leaveAt == null
                || !commuteAt.toLocalDate().equals(date)
                || !leaveAt.toLocalDate().equals(date)
                || leaveAt.isBefore(commuteAt)) {
            return false;
        }

        EffectiveAttendanceRule rule =
                effectiveAttendanceRuleService.resolve(
                        companyId, employeeId, date
                );

        LeaveTimePolicy policy;

        try {
            policy = leaveTimePolicyService.getPolicy(companyId);
        } catch (IllegalStateException e) {
            // 반차 시간 정책이 미설정된 경우 임의 판정하지 않음
            return false;
        }

        LocalTime workStart = rule.workStartTime();
        LocalTime workEnd = rule.workEndTime();

        if (workStart == null || workEnd == null) {
            return false;
        }

        LocalTime actualStart = commuteAt.toLocalTime();
        LocalTime actualEnd = leaveAt.toLocalTime();

        if (morning) {
            LocalTime returnTime = policy.getMorningHalfReturnTime();

            // 오전반차: 복귀시간 이전 출근 + 근무 종료까지 재직
            return returnTime != null
                    && returnTime.isBefore(workEnd)
                    && !actualStart.isAfter(returnTime)
                    && !actualEnd.isBefore(workEnd);
        }

        LocalTime afternoonLeaveTime = policy.getAfternoonHalfLeaveTime();

        // 오후반차: 정상 출근 + 반차 퇴근 기준까지 재직
        return afternoonLeaveTime != null
                && afternoonLeaveTime.isAfter(workStart)
                && !actualStart.isAfter(workStart)
                && !actualEnd.isBefore(afternoonLeaveTime);
    }

    private static boolean coversDate(AttendanceRecord record, LocalDate date) {
        return record.getStartDate() != null
                && record.getEndDate() != null
                && !date.isBefore(record.getStartDate())
                && !date.isAfter(record.getEndDate());
    }

    private static boolean coversDate(AttendanceEvent event, LocalDate date) {
        LocalDate end = event.getActualEndDate() != null
                ? event.getActualEndDate()
                : event.getEndDate();

        return event.getStartDate() != null
                && end != null
                && !date.isBefore(event.getStartDate())
                && !date.isAfter(end);
    }
}
