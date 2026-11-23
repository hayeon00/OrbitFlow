
package com.finalproj.orbitflow.attendance.rule.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalTime;

public record DefaultRuleUpdateReqDto(
        LocalTime defaultStartTime,
        LocalTime defaultEndTime,

        @Min(value = 0, message = "휴게 시간은 0분 이상이어야 합니다.")
        @Max(value = 480, message = "휴게 시간은 480분 이하여야 합니다.")
        Integer defaultBreakMinutes,

        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
}
