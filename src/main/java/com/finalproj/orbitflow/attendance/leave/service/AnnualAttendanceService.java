
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.attendance.leave.service.DailyAttendanceClassifier.DayResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 정기 연차용 연간 출근율 집계.
 *
 * 날짜별 판정은 DailyAttendanceClassifier가 담당하고,
 * 이 서비스는 결과를 일수로 센다.
 *
 * - scheduledDays: 소정근로일 (NOT_SCHEDULED 제외 전부)
 * - attendedDays: 출근 또는 출근 인정일
 * - undeterminedDays: 판정 보류일
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnualAttendanceService {

    private final DailyAttendanceClassifier dailyAttendanceClassifier;

    public record AnnualAttendanceSummary(
            int scheduledDays,
            int attendedDays,
            int undeterminedDays
    ) {
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

        int scheduled = 0;
        int attended = 0;
        int undetermined = 0;

        for (DayResult day : dailyAttendanceClassifier
                .classify(companyId, employeeId, startDate, endDate)
                .values()) {

            switch (day) {
                case NOT_SCHEDULED -> {
                }
                case ATTENDED -> {
                    scheduled++;
                    attended++;
                }
                case UNDETERMINED -> {
                    scheduled++;
                    undetermined++;
                }
                case ABSENT -> scheduled++;
            }
        }

        return new AnnualAttendanceSummary(scheduled, attended, undetermined);
    }
}
