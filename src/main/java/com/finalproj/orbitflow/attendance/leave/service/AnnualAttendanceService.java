
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
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.global.time.calendar.entity.CalendarDay;
import com.finalproj.orbitflow.global.time.calendar.enums.CalendarDayType;
import com.finalproj.orbitflow.global.time.calendar.repository.CalendarDayRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnualAttendanceService {

    private final CalendarDayRepository calendarDayRepository;
    private final CommuteRepository commuteRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceEventRepository attendanceEventRepository;
    private final MonthlyAttendanceService monthlyAttendanceService;

    @Value("${orbitflow.leave.review-credit-types:}")
    private String reviewCreditTypes;

    public record AnnualAttendanceSummary(
            int scheduledDays,
            int attendedDays,
            int undeterminedDays
    ) {
    }

    private enum DayResult {
        ATTENDED,
        ABSENT,
        UNDETERMINED
    }

    public AnnualAttendanceSummary evaluate(
            Long companyId,
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Objects.requireNonNull(companyId, "회사 ID가 없습니다.");
        Objects.requireNonNull(employeeId, "직원 ID가 없습니다.");

        if (startDate == null || endDate == null
                || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "연간 근태 평가 기간이 올바르지 않습니다."
            );
        }

        // 평가 기간 전체를 한 번에 조회한다.
        Map<LocalDate, CalendarDay> calendarMap =
                calendarDayRepository
                        .findByDateBetweenOrderByDateAsc(startDate, endDate)
                        .stream()
                        .collect(Collectors.toMap(
                                CalendarDay::getDate,
                                Function.identity()
                        ));

        Map<LocalDate, Attendance> attendanceMap =
                commuteRepository
                        .findByCompanyIdAndEmployeeIdAndWorkDateBetween(
                                companyId, employeeId, startDate, endDate
                        )
                        .stream()
                        .collect(Collectors.toMap(
                                Attendance::getWorkDate,
                                Function.identity()
                        ));

        List<AttendanceRecord> leaveRecords =
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

        List<AttendanceEvent> workEvents =
                attendanceEventRepository
                        .findApprovedOverlappingWorkEvents(
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

        int scheduled = 0;
        int attended = 0;
        int undetermined = 0;

        for (LocalDate date = startDate;
             !date.isAfter(endDate);
             date = date.plusDays(1)) {

            DayOfWeek dayOfWeek = date.getDayOfWeek();

            // 현재 시스템의 월~금 근무제와 일치시킨다.
            if (dayOfWeek == DayOfWeek.SATURDAY
                    || dayOfWeek == DayOfWeek.SUNDAY) {
                continue;
            }

            CalendarDay calendarDay = calendarMap.get(date);

            // 달력 누락은 임의로 휴일로 처리하지 않는다.
            if (calendarDay == null) {
                scheduled++;
                undetermined++;
                continue;
            }

            CalendarDayType dayType = calendarDay.getDayType();

            if (dayType == CalendarDayType.PUBLIC_HOLIDAY
                    || dayType == CalendarDayType.PAID_HOLIDAY) {
                continue;
            }

            scheduled++;

            if (dayType != CalendarDayType.WORKDAY) {
                undetermined++;
                continue;
            }

// 결재 진행 중인 휴가가 있는 소정근로일은
// 승인 또는 반려가 확정되기 전까지 출근율 산정을 보류한다.
            final LocalDate currentDate = date;

            boolean hasPendingLeave = pendingRecords.stream()
                    .anyMatch(record ->
                            record.getStartDate() != null
                                    && record.getEndDate() != null
                                    && !currentDate.isBefore(record.getStartDate())
                                    && !currentDate.isAfter(record.getEndDate())
                    );

            if (hasPendingLeave) {
                undetermined++;
                continue;
            }

            DayResult result = evaluateDay(
                    companyId,
                    employeeId,
                    date,
                    attendanceMap.get(date),
                    leaveRecords,
                    workEvents
            );


            switch (result) {
                case ATTENDED -> attended++;
                case UNDETERMINED -> undetermined++;
                case ABSENT -> {
                    // 소정근로일에는 이미 포함되어 있으며,
                    // 출근 인정일에는 포함하지 않는다.
                }
            }
        }

        return new AnnualAttendanceSummary(
                scheduled, attended, undetermined
        );
    }

    private DayResult evaluateDay(
            Long companyId,
            Long employeeId,
            LocalDate date,
            Attendance attendance,
            List<AttendanceRecord> leaveRecords,
            List<AttendanceEvent> workEvents
    ) {
        List<AttendanceRecord> activeLeaves =
                leaveRecords.stream()
                        .filter(record ->
                                record.getStatus()
                                        == DocumentStatus.APPROVED)
                        .filter(record ->
                                !date.isBefore(record.getStartDate())
                                        && !date.isAfter(record.getEndDate()))
                        .toList();

        List<AttendanceEvent> activeWorkEvents =
                workEvents.stream()
                        .filter(event -> {
                            LocalDate end =
                                    event.getActualEndDate() != null
                                            ? event.getActualEndDate()
                                            : event.getEndDate();

                            return !date.isBefore(event.getStartDate())
                                    && !date.isAfter(end);
                        })
                        .toList();

        // 여러 승인 기록이 같은 날짜에 중첩되면 임의로 우선순위를
        // 정하지 않고 근태를 미확정으로 처리한다.
        if (activeLeaves.size() + activeWorkEvents.size() > 1) {
            return DayResult.UNDETERMINED;
        }

        if (!activeLeaves.isEmpty()) {
            AttendanceRecord record = activeLeaves.get(0);

            if (record.getLeaveType() == null
                    || record.getLeaveType().getAttendancePolicy() == null) {
                return DayResult.UNDETERMINED;
            }

            LeaveAttendancePolicy policy =
                    record.getLeaveType().getAttendancePolicy();



            if (policy == LeaveAttendancePolicy.ATTENDANCE_CREDIT) {

                // 월별 개근 평가와 동일한 조건 적용:
                // 최종 승인 + 연차 차감 대상 + 출근 인정 정책 + 1일 단위
                if (!ApprovedFullDayLeavePolicy.isRecognized(
                        record,
                        date
                )) {
                    return DayResult.UNDETERMINED;
                }

                // 승인된 종일 연차에 대해서만
                // 기록 누락, VACATION, 기존 ABSENT를 출근 인정한다.
                if (attendance == null
                        || attendance.getStatus() == AttendanceStatus.VACATION
                        || attendance.getStatus() == AttendanceStatus.ABSENT) {

                    return DayResult.ATTENDED;
                }

                // 승인된 연차와 실제 출근 또는 출장·외근 기록이
                // 충돌한다면 임의로 확정하지 않는다.
                return DayResult.UNDETERMINED;
            }


// 명시적으로 허용된 REVIEW_REQUIRED 종일 휴가
            if (ApprovedReviewLeavePolicy.isRecognized(
                    record,
                    date,
                    reviewCreditTypes
            )) {

                if (attendance == null
                        || attendance.getStatus() == AttendanceStatus.VACATION
                        || attendance.getStatus() == AttendanceStatus.ABSENT) {

                    return DayResult.ATTENDED;
                }

                // 승인 휴가와 실제 출근 기록의 중첩은 보류
                return DayResult.UNDETERMINED;
            }




            if (policy == LeaveAttendancePolicy.PARTIAL_DAY) {
                // 반차의 실제 출퇴근·적용 근무시간 판정은
                // 기존 월별 근태 서비스를 재사용한다.
                MonthlyAttendanceResult result =
                        monthlyAttendanceService.evaluate(
                                companyId, employeeId, date, date
                        );

                return switch (result) {
                    case PERFECT -> DayResult.ATTENDED;
                    case NOT_PERFECT -> DayResult.ABSENT;
                    case UNDETERMINED -> DayResult.UNDETERMINED;
                };
            }

            return DayResult.UNDETERMINED;
        }

        if (!activeWorkEvents.isEmpty()) {
            if (attendance == null) {
                return DayResult.ATTENDED;
            }

            AttendanceStatus status = attendance.getStatus();

            if (status == AttendanceStatus.BUSINESS_TRIP
                    || status == AttendanceStatus.OUTSIDE) {
                return DayResult.ATTENDED;
            }

            return DayResult.UNDETERMINED;
        }

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
}
