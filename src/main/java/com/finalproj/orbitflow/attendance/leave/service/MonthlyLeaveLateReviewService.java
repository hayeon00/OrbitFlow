
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyLeaveLateReviewService {

    private static final String GRANT_TYPE = "ANNUAL_MONTHLY";

    private final EmployeeRepository employeeRepository;
    private final LeaveGrantRepository leaveGrantRepository;
    private final MonthlyAttendanceService monthlyAttendanceService;

    public enum ReviewStatus {

        // 해당 발생일의 연차 이력이 이미 존재함
        ALREADY_GRANTED,

        // 당시 개근 조건을 충족했지만 발생 이력이 없음
        // 현재 잔여량 자동 지급 대상이라는 의미는 아님
        ELIGIBLE_FOR_REVIEW,

        // 결재 대기 또는 근태 판정 미완료
        UNDETERMINED,

        // 당시 개근 조건을 충족하지 못함
        NOT_PERFECT
    }

    public record ReviewResult(
            Long employeeId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate grantDate,
            LocalDate expirationDate,
            ReviewStatus status
    ) {
    }

    /**
     * 최초 1년간 발생할 수 있었던 월별 연차 11건을 재검사한다.
     *
     * 입사 1주년 이후에만 호출 가능하다.
     *
     * DB 읽기 전용:
     * - 발생 이력 INSERT 없음
     * - 잔여량 UPDATE 없음
     * - 만료 기한 변경 없음
     */
    public List<ReviewResult> review(
            Long employeeId,
            LocalDate asOfDate
    ) {
        Objects.requireNonNull(employeeId, "사원 ID는 필수입니다.");
        Objects.requireNonNull(asOfDate, "검사 기준일은 필수입니다.");

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new NotFoundException("사원을 찾을 수 없습니다.")
                );

        LocalDate hireDate = employee.getHireDate();

        if (hireDate == null) {
            throw new IllegalStateException(
                    "입사일이 없는 직원은 평가할 수 없습니다."
            );
        }

        LocalDate anniversary = hireDate.plusYears(1);

        if (asOfDate.isBefore(anniversary)) {
            throw new IllegalArgumentException(
                    "입사 1주년 이후에만 사후 재검사가 가능합니다."
            );
        }

        Long companyId = employee.getCompany().getId();
        LocalDate expirationDate = anniversary.minusDays(1);

        List<ReviewResult> results = new ArrayList<>();

        for (int month = 1; month <= 11; month++) {

            LocalDate grantDate = hireDate.plusMonths(month);
            LocalDate periodStart = hireDate.plusMonths(month - 1);
            LocalDate periodEnd = grantDate.minusDays(1);

            boolean alreadyGranted =
                    leaveGrantRepository
                            .existsByEmployeeIdAndGrantTypeAndGrantDate(
                                    employeeId,
                                    GRANT_TYPE,
                                    grantDate
                            );

            ReviewStatus status;

            if (alreadyGranted) {
                status = ReviewStatus.ALREADY_GRANTED;

            } else {
                MonthlyAttendanceResult attendanceResult =
                        monthlyAttendanceService.evaluate(
                                companyId,
                                employeeId,
                                periodStart,
                                periodEnd
                        );

                status = switch (attendanceResult) {

                    case PERFECT ->
                            ReviewStatus.ELIGIBLE_FOR_REVIEW;

                    case UNDETERMINED ->
                            ReviewStatus.UNDETERMINED;

                    case NOT_PERFECT ->
                            ReviewStatus.NOT_PERFECT;
                };
            }

            results.add(
                    new ReviewResult(
                            employeeId,
                            periodStart,
                            periodEnd,
                            grantDate,
                            expirationDate,
                            status
                    )
            );
        }

        return List.copyOf(results);
    }
}
