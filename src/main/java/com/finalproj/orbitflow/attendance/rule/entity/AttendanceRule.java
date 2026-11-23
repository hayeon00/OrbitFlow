package com.finalproj.orbitflow.attendance.rule.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Please explain the class!!!
 *
 * @author : rlagkdus
 * @filename : AttendanceRule
 * @since : 2025. 12. 22. 월요일
 */


@Entity
@Table(
        name = "attendance_rule",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_attendance_rule_company",
                        columnNames = "company_id"
                )
        }
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(length = 100)
    private String name;

    @Column(name = "default_start_time", nullable = false)
    private LocalTime defaultStartTime;

    @Column(name = "default_end_time", nullable = false)
    private LocalTime defaultEndTime;

    @Column(name = "default_break_minutes")
    private Integer defaultBreakMinutes;

    @Column(name = "late_threshold_min")
    private Integer lateThresholdMin;

    @Column(name = "early_leave_threshold_min")
    private Integer earlyLeaveThresholdMin;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = true;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "break_start_time")
    private LocalTime breakStartTime;

    @Column(name = "break_end_time")
    private LocalTime breakEndTime;


    public void updateRule(
            LocalTime startTime,
            LocalTime endTime,
            Integer breakMinutes
    ) {

        LocalTime newStartTime =
                startTime != null ? startTime : this.defaultStartTime;

        LocalTime newEndTime =
                endTime != null ? endTime : this.defaultEndTime;

        Integer newBreakMinutes =
                breakMinutes != null ? breakMinutes : this.defaultBreakMinutes;

        validateWorkTimes(newStartTime, newEndTime);
        validateBreakMinutes(newBreakMinutes);

        this.defaultStartTime = newStartTime;
        this.defaultEndTime = newEndTime;
        this.defaultBreakMinutes = newBreakMinutes;

        this.updatedAt = LocalDateTime.now();
    }

    private void validateWorkTimes(LocalTime start, LocalTime end) {
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

    private void validateBreakMinutes(Integer minutes) {
        if (minutes != null && (minutes < 0 || minutes > 480)) {
            throw new IllegalArgumentException(
                    "휴게 시간은 0분에서 480분 사이여야 합니다."
            );
        }
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }


    public void updateLunchBreak(
            LocalTime breakStartTime,
            LocalTime breakEndTime
    ) {
        if (breakStartTime == null || breakEndTime == null) {
            throw new IllegalArgumentException(
                    "휴게 시작·종료시간은 필수입니다."
            );
        }

        if (!breakStartTime.isBefore(breakEndTime)) {
            throw new IllegalArgumentException(
                    "휴게 종료시간은 시작시간보다 늦어야 합니다."
            );
        }

        if (breakStartTime.isBefore(defaultStartTime)
                || breakEndTime.isAfter(defaultEndTime)) {
            throw new IllegalArgumentException(
                    "휴게시간은 근무시간 안에 있어야 합니다."
            );
        }

        this.breakStartTime = breakStartTime;
        this.breakEndTime = breakEndTime;
        this.defaultBreakMinutes = (int)
                java.time.Duration.between(
                        breakStartTime,
                        breakEndTime
                ).toMinutes();
    }
}