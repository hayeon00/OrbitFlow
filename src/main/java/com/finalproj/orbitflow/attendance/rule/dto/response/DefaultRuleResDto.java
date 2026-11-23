
package com.finalproj.orbitflow.attendance.rule.dto.response;

import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;

import java.time.LocalTime;

public record DefaultRuleResDto(
        LocalTime defaultStartTime,
        LocalTime defaultEndTime,
        Integer defaultBreakMinutes,
        LocalTime breakStartTime,
        LocalTime breakEndTime
) {

    public DefaultRuleResDto(AttendanceRule rule) {
        this(
                rule.getDefaultStartTime(),
                rule.getDefaultEndTime(),
                rule.getDefaultBreakMinutes(),
                rule.getBreakStartTime(),
                rule.getBreakEndTime()
        );
    }
}
