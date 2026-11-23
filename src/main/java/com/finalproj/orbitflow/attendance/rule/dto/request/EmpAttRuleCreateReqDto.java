
package com.finalproj.orbitflow.attendance.rule.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;

public record EmpAttRuleCreateReqDto(
        Long employeeId,
        LocalTime startTime,
        LocalTime endTime,
        Integer breakMinutes,
        String reason,
        LocalDate validFrom,
        LocalDate validTo,

        LocalTime breakStartTime,
        LocalTime breakEndTime
) {
}
