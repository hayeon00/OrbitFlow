
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovedFullDayLeavePolicyTest {

    private static final LocalDate TARGET =
            LocalDate.of(2026, 10, 7);

    private AttendanceRecord record(
            DocumentStatus status,
            Boolean isCountable,
            LeaveAttendancePolicy policy,
            BigDecimal unitDays
    ) {
        AttendanceRecord record =
                mock(AttendanceRecord.class);

        LeaveType leaveType =
                mock(LeaveType.class);

        when(record.getStatus())
                .thenReturn(status);

        if (status != DocumentStatus.APPROVED) {
            return record;
        }

        when(record.getStartDate())
                .thenReturn(TARGET);

        when(record.getEndDate())
                .thenReturn(TARGET);

        when(record.getLeaveType())
                .thenReturn(leaveType);

        when(leaveType.getIsCountable())
                .thenReturn(isCountable);

        if (!Boolean.TRUE.equals(isCountable)) {
            return record;
        }

        when(leaveType.getAttendancePolicy())
                .thenReturn(policy);

        if (policy != LeaveAttendancePolicy.ATTENDANCE_CREDIT) {
            return record;
        }

        when(leaveType.getUnitDays())
                .thenReturn(unitDays);

        return record;
    }

    @Test
    void 승인된_종일연차는_출근인정한다() {

        AttendanceRecord approved = record(
                DocumentStatus.APPROVED,
                true,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT,
                BigDecimal.ONE
        );

        assertTrue(
                ApprovedFullDayLeavePolicy.isRecognized(
                        approved,
                        TARGET
                )
        );
    }

    @Test
    void 승인대기_휴가는_종일연차로_인정하지않는다() {

        AttendanceRecord pending = record(
                DocumentStatus.IN_PROGRESS,
                true,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT,
                BigDecimal.ONE
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        pending,
                        TARGET
                )
        );
    }

    @Test
    void 반려된_휴가는_인정하지않는다() {

        AttendanceRecord rejected = record(
                DocumentStatus.REJECTED,
                true,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT,
                BigDecimal.ONE
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        rejected,
                        TARGET
                )
        );
    }

    @Test
    void 차감대상이_아닌_휴가는_종일연차로_인정하지않는다() {

        AttendanceRecord approved = record(
                DocumentStatus.APPROVED,
                false,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT,
                BigDecimal.ONE
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        approved,
                        TARGET
                )
        );
    }

    @Test
    void 반차는_종일연차로_인정하지않는다() {

        AttendanceRecord approved = record(
                DocumentStatus.APPROVED,
                true,
                LeaveAttendancePolicy.PARTIAL_DAY,
                new BigDecimal("0.5")
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        approved,
                        TARGET
                )
        );
    }

    @Test
    void 검토필요_휴가는_자동인정하지않는다() {

        AttendanceRecord approved = record(
                DocumentStatus.APPROVED,
                false,
                LeaveAttendancePolicy.REVIEW_REQUIRED,
                BigDecimal.ONE
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        approved,
                        TARGET
                )
        );
    }

    @Test
    void 단위일수가_1일이_아니면_종일연차로_인정하지않는다() {

        AttendanceRecord approved = record(
                DocumentStatus.APPROVED,
                true,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT,
                new BigDecimal("0.5")
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        approved,
                        TARGET
                )
        );
    }

    @Test
    void 승인된_휴가라도_적용일이_아니면_인정하지않는다() {

        AttendanceRecord approved = record(
                DocumentStatus.APPROVED,
                true,
                LeaveAttendancePolicy.ATTENDANCE_CREDIT,
                BigDecimal.ONE
        );

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        approved,
                        TARGET.plusDays(1)
                )
        );
    }

    @Test
    void 휴가기록이_없으면_인정하지않는다() {

        assertFalse(
                ApprovedFullDayLeavePolicy.isRecognized(
                        null,
                        TARGET
                )
        );
    }
}
