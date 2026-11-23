
package com.finalproj.orbitflow.attendance.leave.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record LeaveTimePolicyUpdateReqDto(

        @NotNull(message = "오전반차 출근시간은 필수입니다.")
        LocalTime morningHalfReturnTime,

        @NotNull(message = "오후반차 퇴근시간은 필수입니다.")
        LocalTime afternoonHalfLeaveTime

) {
}
