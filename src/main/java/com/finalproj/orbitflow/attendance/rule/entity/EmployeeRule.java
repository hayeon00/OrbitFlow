
package com.finalproj.orbitflow.attendance.rule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "employee_att_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmployeeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "break_minutes")
    private Integer breakMinutes;

    @Column(length = 255)
    private String reason;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    public static EmployeeRule create(
            Long companyId,
            Long employeeId,
            LocalTime startTime,
            LocalTime endTime,
            Integer breakMinutes,
            String reason,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        validateIdentity(companyId, employeeId);
        validateRule(startTime, endTime,
                breakMinutes, validFrom, validTo);

        EmployeeRule rule = new EmployeeRule();
        rule.companyId = companyId;
        rule.employeeId = employeeId;
        rule.startTime = startTime;
        rule.endTime = endTime;
        rule.breakMinutes = breakMinutes;
        rule.reason = reason;
        rule.validFrom = validFrom;
        rule.validTo = validTo;
        rule.isActive = true;
        rule.appliedAt = LocalDateTime.now();

        return rule;
    }

    public void updateRule(
            LocalTime startTime,
            LocalTime endTime,
            Integer breakMinutes,
            String reason,
            LocalDate validFrom,
            LocalDate validTo,
            Boolean isActive
    ) {
        validateRule(startTime, endTime,
                breakMinutes, validFrom, validTo);

        this.startTime = startTime;
        this.endTime = endTime;
        this.breakMinutes = breakMinutes;
        this.reason = reason;
        this.validFrom = validFrom;
        this.validTo = validTo;

        if (isActive != null) {
            this.isActive = isActive;
        }

        this.appliedAt = LocalDateTime.now();
    }

    public void delete() {
        this.isActive = false;
        this.appliedAt = LocalDateTime.now();
    }

    private static void validateIdentity(
            Long companyId, Long employeeId
    ) {
        if (companyId == null || employeeId == null) {
            throw new IllegalArgumentException(
                    "회사 ID와 사원 ID는 필수입니다."
            );
        }
    }

    private static void validateRule(
            LocalTime startTime,
            LocalTime endTime,
            Integer breakMinutes,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        validateDateRange(validFrom, validTo);
        validateWorkTimes(startTime, endTime);
        validateBreakMinutes(breakMinutes);
    }

    private static void validateDateRange(
            LocalDate from, LocalDate to
    ) {
        if (from == null) {
            throw new IllegalArgumentException(
                    "규칙 적용 시작일은 필수입니다."
            );
        }

        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "종료일은 시작일보다 빠를 수 없습니다."
            );
        }
    }

    private static void validateWorkTimes(
            LocalTime start, LocalTime end
    ) {
        if (start == null || end == null) {
            throw new IllegalArgumentException(
                    "출근 시간과 퇴근 시간은 필수입니다."
            );
        }

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "퇴근 시간은 출근 시간보다 이후여야 합니다."
            );
        }
    }

    private static void validateBreakMinutes(Integer minutes) {
        if (minutes != null &&
                (minutes < 0 || minutes > 480)) {
            throw new IllegalArgumentException(
                    "휴게 시간은 0분에서 480분 사이여야 합니다."
            );
        }
    }
}
