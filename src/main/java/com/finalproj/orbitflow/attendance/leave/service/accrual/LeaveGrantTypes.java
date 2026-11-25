
package com.finalproj.orbitflow.attendance.leave.service.accrual;

/**
 * grant_history.grant_type 값.
 *
 * 중복 지급 판단(uk_leave_grant_event)의 키이므로
 * 값을 바꾸면 기존 이력과의 중복 검사가 깨진다.
 */
public final class LeaveGrantTypes {

    /** 입사 1년 미만 월별 개근 연차 (1일) */
    public static final String MONTHLY = "ANNUAL_MONTHLY";

    /** 입사 1주년 이후 정기 연차 (15~25일) */
    public static final String REGULAR = "ANNUAL_REGULAR";

    private LeaveGrantTypes() {
    }
}
