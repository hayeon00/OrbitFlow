
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 월별 개근 평가와 연간 출근율 평가에서
 * 공통으로 사용하는 승인된 종일 연차 인정 규칙.
 *
 * 실제 출퇴근 데이터는 수정하지 않는다.
 */
public final class ApprovedFullDayLeavePolicy {

    private ApprovedFullDayLeavePolicy() {
    }

    public static boolean isRecognized(
            AttendanceRecord record,
            LocalDate date
    ) {
        if (record == null || date == null) {
            return false;
        }

        if (record.getStatus() != DocumentStatus.APPROVED) {
            return false;
        }

        if (record.getStartDate() == null
                || record.getEndDate() == null
                || date.isBefore(record.getStartDate())
                || date.isAfter(record.getEndDate())) {
            return false;
        }

        LeaveType leaveType = record.getLeaveType();

        if (leaveType == null) {
            return false;
        }

        if (!Boolean.TRUE.equals(
                leaveType.getIsCountable())) {
            return false;
        }

        if (leaveType.getAttendancePolicy()
                != LeaveAttendancePolicy.ATTENDANCE_CREDIT) {
            return false;
        }

        return leaveType.getUnitDays() != null
                && leaveType.getUnitDays()
                .compareTo(BigDecimal.ONE) == 0;
    }
}
