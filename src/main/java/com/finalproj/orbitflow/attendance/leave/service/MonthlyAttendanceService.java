
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
import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
import com.finalproj.orbitflow.global.time.calendar.entity.CalendarDay;
import com.finalproj.orbitflow.global.time.calendar.enums.CalendarDayType;
import com.finalproj.orbitflow.global.time.calendar.repository.CalendarDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyAttendanceService {

    private final CalendarDayRepository calendarDayRepository;
    private final CommuteRepository commuteRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceEventRepository attendanceEventRepository;

    // 추가: 날짜별 실제 근무 규칙과 회사 반차 정책
    private final EffectiveAttendanceRuleService effectiveAttendanceRuleService;
    private final LeaveTimePolicyService leaveTimePolicyService;

    @Value("${orbitflow.leave.review-credit-types:}")
    private String reviewCreditTypes;

    public MonthlyAttendanceResult evaluate(
            Long companyId,
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate
    ) {

        if (startDate == null || endDate == null
                || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("잘못된 개근 판정 기간입니다.");
        }

        List<CalendarDay> calendarDays =
                calendarDayRepository.findByDateBetweenOrderByDateAsc(
                        startDate, endDate
                );

        Map<LocalDate, CalendarDay> calendarMap =
                calendarDays.stream().collect(Collectors.toMap(
                        CalendarDay::getDate,
                        Function.identity()
                ));

        List<Attendance> attendanceList =
                commuteRepository
                        .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                                companyId,
                                employeeId,
                                startDate,
                                endDate
                        );

        Map<LocalDate, Attendance> attendanceMap =
                attendanceList.stream().collect(Collectors.toMap(
                        Attendance::getWorkDate,
                        Function.identity()
                ));

        List<AttendanceRecord> approvedRecords =
                attendanceRecordRepository.findOverlappingRecords(
                        companyId,
                        employeeId,
                        DocumentStatus.APPROVED,
                        startDate,
                        endDate
                );

        List<AttendanceRecord> pendingRecords =
                attendanceRecordRepository.findOverlappingRecords(
                        companyId,
                        employeeId,
                        DocumentStatus.IN_PROGRESS,
                        startDate,
                        endDate
                );

        List<AttendanceEvent> approvedWorkEvents =
                attendanceEventRepository.findApprovedOverlappingWorkEvents(
                        companyId,
                        employeeId,
                        List.of(
                                BaseRole.BUSINESS_TRIP,
                                BaseRole.OUTWORK
                        ),
                        DocumentStatus.APPROVED,
                        startDate,
                        endDate
                );

        boolean hasUndeterminedDay = false;

        for (LocalDate date = startDate;
             !date.isAfter(endDate);
             date = date.plusDays(1)) {

            CalendarDay calendarDay = calendarMap.get(date);

            if (calendarDay == null) {
                hasUndeterminedDay = true;
                continue;
            }

            DayOfWeek dayOfWeek = date.getDayOfWeek();

            if (dayOfWeek == DayOfWeek.SATURDAY
                    || dayOfWeek == DayOfWeek.SUNDAY) {
                continue;
            }

            CalendarDayType dayType = calendarDay.getDayType();

            if (dayType == CalendarDayType.PUBLIC_HOLIDAY
                    || dayType == CalendarDayType.PAID_HOLIDAY) {
                continue;
            }

            if (dayType == CalendarDayType.UNPAID_HOLIDAY) {
                hasUndeterminedDay = true;
                continue;
            }

            Attendance attendance = attendanceMap.get(date);

            final LocalDate currentDate = date;

            // 승인 대기 중인 휴가가 적용된 날짜는
// 실제 결재 결과가 확정될 때까지 개근 판정을 보류한다.
            boolean hasPendingLeave = pendingRecords.stream()
                    .anyMatch(record ->
                            record.getStartDate() != null
                                    && record.getEndDate() != null
                                    && !currentDate.isBefore(record.getStartDate())
                                    && !currentDate.isAfter(record.getEndDate())
                    );

            if (hasPendingLeave) {
                hasUndeterminedDay = true;
                continue;
            }

            boolean approvedWork = approvedWorkEvents.stream()
                    .anyMatch(event ->
                            !currentDate.isBefore(event.getStartDate())
                                    && !currentDate.isAfter(
                                    event.getActualEndDate() != null
                                            ? event.getActualEndDate()
                                            : event.getEndDate()
                            )
                    );

            // 해당 날짜에 적용되는 승인 휴가 목록
            List<AttendanceRecord> dailyLeaves =
                    approvedRecords.stream()
                            .filter(record -> coversDate(record, currentDate))
                            .toList();

            boolean approvedFullDayLeave =
                    hasApprovedFullDayAnnualLeave(
                            dailyLeaves,
                            date
                    );


            if (approvedFullDayLeave) {

                // 여러 휴가 기록이 중복되거나 출장·외근과 겹치면 보류한다.
                if (dailyLeaves.size() != 1 || approvedWork) {
                    hasUndeterminedDay = true;
                    continue;
                }

                // 최종 승인된 종일 연차는 실제 출근하지 않은 날을
                // 근태상 출근 인정일로 처리한다.
                //
                // 기존 ABSENT 기록은 수정하거나 삭제하지 않는다.
                // 개근 평가에서만 승인된 종일 연차를 우선 적용한다.
                if (attendance == null
                        || attendance.getStatus() == AttendanceStatus.VACATION
                        || attendance.getStatus() == AttendanceStatus.ABSENT) {
                    continue;
                }

                // 승인 연차와 실제 출근·출장 등의 기록이 충돌하는 경우
                // 임의로 우선순위를 정하지 않고 검토 대상으로 보류한다.
                hasUndeterminedDay = true;
                continue;
            }


            // 새로 추가한 오전반차·오후반차 처리
            if (!dailyLeaves.isEmpty()) {

                // 현재 구현에서는 같은 날 복수 휴가를 자동 합산하지 않음
                if (dailyLeaves.size() != 1 || approvedWork) {
                    hasUndeterminedDay = true;
                    continue;
                }

                AttendanceRecord leave = dailyLeaves.get(0);

                LeaveType leaveType = leave.getLeaveType();

                if (leaveType != null
                        && leaveType.getAttendancePolicy()
                        == LeaveAttendancePolicy.PARTIAL_DAY) {

                    MonthlyAttendanceResult partialResult =
                            evaluatePartialDay(
                                    companyId,
                                    employeeId,
                                    date,
                                    attendance,
                                    leaveType
                            );

                    if (partialResult
                            == MonthlyAttendanceResult.NOT_PERFECT) {
                        return MonthlyAttendanceResult.NOT_PERFECT;
                    }

                    if (partialResult
                            == MonthlyAttendanceResult.UNDETERMINED) {
                        hasUndeterminedDay = true;
                    }

                    continue;
                }


// 회사에서 명시적으로 출근 인정한
// REVIEW_REQUIRED 종일 휴가인지 확인한다.
                if (ApprovedReviewLeavePolicy.isRecognized(
                        leave,
                        date,
                        reviewCreditTypes
                )) {

                    // 승인 휴가가 실제 출근하지 않은 날을 대체한다.
                    // 기존 ABSENT 기록은 변경하지 않는다.
                    if (attendance == null
                            || attendance.getStatus() == AttendanceStatus.VACATION
                            || attendance.getStatus() == AttendanceStatus.ABSENT) {
                        continue;
                    }

                    // 실제 출근 기록과 승인 종일 휴가가 중첩되면 보류
                    hasUndeterminedDay = true;
                    continue;
                }

// 허용되지 않은 휴가 유형은 기존대로 미확정
                hasUndeterminedDay = true;
                continue;

            }

            // 이하 기존 일반 근태 판정
            if (attendance == null) {

                if (approvedWork) {
                    continue;
                }

                hasUndeterminedDay = true;
                continue;
            }

            AttendanceStatus status = attendance.getStatus();

            if (status == AttendanceStatus.ABSENT) {

                if (approvedWork) {
                    hasUndeterminedDay = true;
                    continue;
                }

                return MonthlyAttendanceResult.NOT_PERFECT;
            }

            if (status == AttendanceStatus.ON_TIME
                    || status == AttendanceStatus.LATE) {

                if (attendance.getCommuteAt() == null) {
                    hasUndeterminedDay = true;
                }

                continue;
            }

            if (status == AttendanceStatus.BUSINESS_TRIP
                    || status == AttendanceStatus.OUTSIDE) {

                if (approvedWork) {
                    continue;
                }

                hasUndeterminedDay = true;
                continue;
            }

            hasUndeterminedDay = true;
        }

        return hasUndeterminedDay
                ? MonthlyAttendanceResult.UNDETERMINED
                : MonthlyAttendanceResult.PERFECT;
    }

    /**
     * 오전반차 / 오후반차의 실제 출퇴근 기록 평가.
     *
     * 기존 LATE 출근 인정 정책을 고려하여,
     * 시간 기준 미충족을 곧바로 결근 확정하지 않고
     * 검토 대상(UNDETERMINED)으로 처리한다.
     */
    private MonthlyAttendanceResult evaluatePartialDay(
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
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        if (leaveType.getUnitDays() == null
                || leaveType.getUnitDays()
                .compareTo(new BigDecimal("0.5")) != 0) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        if (attendance == null) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        AttendanceStatus status = attendance.getStatus();

        if (status == AttendanceStatus.ABSENT) {
            // 승인된 반차와 결근 상태가 충돌
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        if (status != AttendanceStatus.ON_TIME
                && status != AttendanceStatus.LATE) {
            // VACATION 등으로 덮인 기록은 자동 인정 불가
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        LocalDateTime commuteAt = attendance.getCommuteAt();
        LocalDateTime leaveAt = attendance.getLeaveAt();

        if (commuteAt == null || leaveAt == null) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        if (!commuteAt.toLocalDate().equals(date)
                || !leaveAt.toLocalDate().equals(date)
                || leaveAt.isBefore(commuteAt)) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        EffectiveAttendanceRule rule =
                effectiveAttendanceRuleService.resolve(
                        companyId,
                        employeeId,
                        date
                );

        LeaveTimePolicy policy;

        try {
            policy = leaveTimePolicyService.getPolicy(companyId);
        } catch (IllegalStateException e) {
            // 반차 시간 정책이 미설정된 경우 임의 판정하지 않음
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        LocalTime workStart = rule.workStartTime();
        LocalTime workEnd = rule.workEndTime();

        if (workStart == null || workEnd == null) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        LocalTime actualStart = commuteAt.toLocalTime();
        LocalTime actualEnd = leaveAt.toLocalTime();

        if (morning) {

            LocalTime returnTime =
                    policy.getMorningHalfReturnTime();

            if (returnTime == null
                    || !returnTime.isBefore(workEnd)) {
                return MonthlyAttendanceResult.UNDETERMINED;
            }

            // 오전반차: 복귀시간 이전 출근 + 근무 종료까지 재직
            if (!actualStart.isAfter(returnTime)
                    && !actualEnd.isBefore(workEnd)) {
                return MonthlyAttendanceResult.PERFECT;
            }

            return MonthlyAttendanceResult.UNDETERMINED;
        }

        LocalTime afternoonLeaveTime =
                policy.getAfternoonHalfLeaveTime();

        if (afternoonLeaveTime == null
                || !afternoonLeaveTime.isAfter(workStart)) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        // 오후반차: 정상 출근 + 반차 퇴근 기준까지 재직
        if (!actualStart.isAfter(workStart)
                && !actualEnd.isBefore(afternoonLeaveTime)) {
            return MonthlyAttendanceResult.PERFECT;
        }

        return MonthlyAttendanceResult.UNDETERMINED;
    }

    /**
     * 휴가 기록이 해당 날짜를 포함하는지 확인.
     */
    private boolean coversDate(
            AttendanceRecord record,
            LocalDate date
    ) {
        return record.getStatus() == DocumentStatus.APPROVED
                && record.getStartDate() != null
                && record.getEndDate() != null
                && !date.isBefore(record.getStartDate())
                && !date.isAfter(record.getEndDate());
    }


    /**
     * 해당 날짜에 출근 인정 대상인
     * 승인된 종일 연차가 있는지 확인한다.
     */
    private boolean hasApprovedFullDayAnnualLeave(
            List<AttendanceRecord> approvedRecords,
            LocalDate date
    ) {
        return approvedRecords.stream()
                .anyMatch(record ->
                        ApprovedFullDayLeavePolicy.isRecognized(
                                record,
                                date
                        )
                );
    }

}
