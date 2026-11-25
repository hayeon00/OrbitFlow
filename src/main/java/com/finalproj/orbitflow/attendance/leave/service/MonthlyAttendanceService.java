
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.service.DailyAttendanceClassifier.DayResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;

/**
 * 월별 연차용 개근 판정.
 *
 * 날짜별 판정은 DailyAttendanceClassifier가 담당하고,
 * 이 서비스는 결과를 하나로 모은다.
 *
 * - 결근(ABSENT)이 하루라도 있으면 → NOT_PERFECT
 * - 그 외 미확정(UNDETERMINED)이 하루라도 있으면 → UNDETERMINED
 * - 모두 출근(또는 소정근로일 아님)이면 → PERFECT
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyAttendanceService {

    private final DailyAttendanceClassifier dailyAttendanceClassifier;

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

        Collection<DayResult> days =
                dailyAttendanceClassifier
                        .classify(companyId, employeeId, startDate, endDate)
                        .values();

        if (days.contains(DayResult.ABSENT)) {
            return MonthlyAttendanceResult.NOT_PERFECT;
        }

        if (days.contains(DayResult.UNDETERMINED)) {
            return MonthlyAttendanceResult.UNDETERMINED;
        }

        return MonthlyAttendanceResult.PERFECT;
    }
}
