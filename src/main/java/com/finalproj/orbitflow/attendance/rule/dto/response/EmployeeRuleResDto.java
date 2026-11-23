
package com.finalproj.orbitflow.attendance.rule.dto.response;

import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record EmployeeRuleResDto(
        Long overrideId,
        Long employeeId,
        String employeeName,
        String employeeNo,
        LocalTime startTime,
        LocalTime endTime,
        Integer breakMinutes,
        String reason,
        LocalDate validFrom,
        LocalDate validTo,
        LocalDateTime appliedAt,

        LocalTime breakStartTime,
        LocalTime breakEndTime
) {

    public EmployeeRuleResDto(
            EmployeeRule rule,
            String employeeName,
            String employeeNo
    ) {
        this(
                rule.getId(),
                rule.getEmployeeId(),
                employeeName,
                employeeNo,
                rule.getStartTime(),
                rule.getEndTime(),
                rule.getBreakMinutes(),
                rule.getReason(),
                rule.getValidFrom(),
                rule.getValidTo(),
                rule.getAppliedAt(),
                rule.getBreakStartTime(),
                rule.getBreakEndTime()
        );
    }
}
