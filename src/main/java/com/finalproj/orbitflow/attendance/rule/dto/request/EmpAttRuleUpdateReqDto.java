
package com.finalproj.orbitflow.attendance.rule.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;

public record EmpAttRuleUpdateReqDto(
        LocalTime startTime,
        LocalTime endTime,
        Integer breakMinutes,
        String reason,
        LocalDate validFrom,
        LocalDate validTo,
        Boolean isActive,

        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
}
