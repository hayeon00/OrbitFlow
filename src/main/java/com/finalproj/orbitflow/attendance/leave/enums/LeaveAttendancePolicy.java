
package com.finalproj.orbitflow.attendance.leave.enums;

public enum LeaveAttendancePolicy {

    // 법정 또는 회사 규칙에 따라 출근 인정
    ATTENDANCE_CREDIT,

    // 소정근로일수 산정에서 제외하는 유형
    EXCLUDED,

    // 실제 근무 여부를 함께 확인해야 함
    PARTIAL_DAY,

    // 별도 규칙이나 관리자 확인이 필요함
    REVIEW_REQUIRED
}
