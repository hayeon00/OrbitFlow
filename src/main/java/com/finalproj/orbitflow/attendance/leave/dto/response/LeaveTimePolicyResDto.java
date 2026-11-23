
package com.finalproj.orbitflow.attendance.leave.dto.response;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;

import java.time.LocalTime;

public record LeaveTimePolicyResDto(
        Long companyId,
        LocalTime morningHalfReturnTime,
        LocalTime afternoonHalfLeaveTime
) {

    public LeaveTimePolicyResDto(LeaveTimePolicy policy) {
        this(
                policy.getCompanyId(),
                policy.getMorningHalfReturnTime(),
                policy.getAfternoonHalfLeaveTime()
        );
    }
}
