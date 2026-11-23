
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

public final class ApprovedReviewLeavePolicy {

    private ApprovedReviewLeavePolicy() {
    }

    /**
     * 회사 정책상 출근 인정 대상으로 명시된
     * 승인 완료 종일 휴가인지 확인한다.
     *
     * configuredTypes:
     * 쉼표로 구분한 휴가 이름.
     *
     * 예: "경조휴가,보상휴가,포상휴가"
     */
    public static boolean isRecognized(
            AttendanceRecord record,
            LocalDate date,
            String configuredTypes
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

        LeaveType type = record.getLeaveType();

        if (type == null
                || type.getAttendancePolicy()
                != LeaveAttendancePolicy.REVIEW_REQUIRED) {
            return false;
        }

        // 반차·시간 단위 휴가를 종일 출근 인정하지 않는다.
        if (record.getHalfDayPeriod() != null) {
            return false;
        }

        if (type.getUnitDays() == null
                || type.getUnitDays().compareTo(BigDecimal.ONE) != 0) {
            return false;
        }

        String typeName = type.getTypeName();

        if (typeName == null
                || typeName.isBlank()
                || configuredTypes == null
                || configuredTypes.isBlank()) {
            return false;
        }

        return Arrays.stream(configuredTypes.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .anyMatch(typeName::equals);
    }
}
