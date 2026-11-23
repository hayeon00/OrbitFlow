
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

class ApprovedReviewLeavePolicyTest {

    private static final LocalDate DATE =
            LocalDate.of(2026, 10, 7);

    private AttendanceRecord approvedReviewLeave() {

        AttendanceRecord record =
                mock(AttendanceRecord.class);

        LeaveType type =
                mock(LeaveType.class);

        when(record.getStatus())
                .thenReturn(DocumentStatus.APPROVED);

        when(record.getStartDate())
                .thenReturn(DATE);

        when(record.getEndDate())
                .thenReturn(DATE);

        when(record.getLeaveType())
                .thenReturn(type);

        when(type.getAttendancePolicy())
                .thenReturn(LeaveAttendancePolicy.REVIEW_REQUIRED);

        when(type.getUnitDays())
                .thenReturn(BigDecimal.ONE);

        when(type.getTypeName())
                .thenReturn("경조휴가");

        return record;
    }

    @Test
    void 허용목록에_있는_승인휴가는_출근인정한다() {

        assertTrue(
                ApprovedReviewLeavePolicy.isRecognized(
                        approvedReviewLeave(),
                        DATE,
                        "경조휴가,보상휴가"
                )
        );
    }

    @Test
    void 허용목록에_없는_휴가는_인정하지않는다() {

        assertFalse(
                ApprovedReviewLeavePolicy.isRecognized(
                        approvedReviewLeave(),
                        DATE,
                        "보상휴가,포상휴가"
                )
        );
    }

    @Test
    void 허용목록이_비어있으면_인정하지않는다() {

        assertFalse(
                ApprovedReviewLeavePolicy.isRecognized(
                        approvedReviewLeave(),
                        DATE,
                        ""
                )
        );
    }
}
