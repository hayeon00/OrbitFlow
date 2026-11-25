
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import java.time.LocalDate;

/**
 * 입사일 기준 연차 발생일·평가 기간·사용 기한 계산.
 *
 * DB 조회나 상태 없이 날짜만 계산한다.
 * 월별·정기 연차의 날짜 규칙은 이 클래스 한 곳에서만 정의한다.
 *
 * 2월 29일 입사자는 평년에 2월 28일을 기념일로 본다
 * (LocalDate.plusYears / plusMonths 규칙).
 */
public final class AccrualSchedule {

    /** 입사 1년 미만 기간에 발생할 수 있는 월별 연차 건수 */
    public static final int MONTHLY_GRANT_COUNT = 11;

    private AccrualSchedule() {
    }

    // ===== 월별 연차 (입사 1년 미만) =====

    /**
     * 입사 후 completedMonths개월이 되는 날.
     *
     * completedMonths=0 은 입사일(첫 평가 기간의 시작일)이다.
     */
    public static LocalDate monthlyGrantDate(
            LocalDate hireDate,
            int completedMonths
    ) {
        if (hireDate == null
                || completedMonths < 0
                || completedMonths > MONTHLY_GRANT_COUNT) {

            throw new IllegalArgumentException(
                    "입사일 또는 완료 개월 수가 올바르지 않습니다."
            );
        }

        return hireDate.plusMonths(completedMonths);
    }

    /** month번째 월별 연차의 개근 평가 시작일 */
    public static LocalDate monthlyPeriodStart(
            LocalDate hireDate,
            int month
    ) {
        return monthlyGrantDate(hireDate, month - 1);
    }

    /**
     * 월별 연차 사용 기한이자 입사 1년차의 마지막 날.
     *
     * 이 날이 지나면 월별 연차를 새로 지급하지 않는다.
     */
    public static LocalDate firstYearEnd(LocalDate hireDate) {
        return hireDate.plusYears(1).minusDays(1);
    }

    /** today가 입사일 ~ 입사 1년차 마지막 날 사이인지 */
    public static boolean isInFirstYear(
            LocalDate hireDate,
            LocalDate today
    ) {
        return hireDate != null
                && !hireDate.isAfter(today)
                && !today.isAfter(firstYearEnd(hireDate));
    }

    // ===== 정기 연차 (입사 1주년 이후) =====

    /** grantDate 연도 기준 근속연수 (연도 차이) */
    public static int yearsOfService(
            LocalDate hireDate,
            LocalDate grantDate
    ) {
        return grantDate.getYear() - hireDate.getYear();
    }

    /** grantDate가 입사 1주년 이후의 정확한 입사 기념일인지 */
    public static boolean isRegularGrantDate(
            LocalDate hireDate,
            LocalDate grantDate
    ) {
        if (hireDate == null || grantDate == null) {
            return false;
        }

        int years = yearsOfService(hireDate, grantDate);

        return years >= 1
                && hireDate.plusYears(years).equals(grantDate);
    }

    /**
     * today 이전(당일 포함)의 가장 최근 입사 기념일.
     *
     * 예: 2024-11-05 입사, today=2026-12-01 → 2026-11-05
     *     2024-11-05 입사, today=2026-11-04 → 2025-11-05
     *
     * 입사일이 없거나 아직 1주년 전이면 null.
     */
    public static LocalDate latestRegularGrantDate(
            LocalDate hireDate,
            LocalDate today
    ) {
        if (hireDate == null || today == null) {
            return null;
        }

        int years = yearsOfService(hireDate, today);

        if (hireDate.plusYears(years).isAfter(today)) {
            years--;
        }

        if (years < 1) {
            return null;
        }

        return hireDate.plusYears(years);
    }

    /** 정기 연차 출근율 평가 시작일 (직전 입사 기념일 또는 입사일) */
    public static LocalDate regularPeriodStart(
            LocalDate hireDate,
            LocalDate grantDate
    ) {
        return hireDate.plusYears(
                yearsOfService(hireDate, grantDate) - 1
        );
    }

    /** 정기 연차 사용 기한 */
    public static LocalDate regularExpirationDate(LocalDate grantDate) {
        return grantDate.plusYears(1).minusDays(1);
    }
}
