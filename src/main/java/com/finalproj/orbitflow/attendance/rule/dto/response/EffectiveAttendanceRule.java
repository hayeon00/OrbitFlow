package com.finalproj.orbitflow.attendance.rule.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;

public record EffectiveAttendanceRule(
        Long companyId,
        Long employeeId,
        LocalDate workDate,

        LocalTime workStartTime,
        LocalTime workEndTime,

        LocalTime breakStartTime,
        LocalTime breakEndTime,
        Integer breakMinutes,

        boolean employeeOverride,
        Long appliedRuleId
) {
}