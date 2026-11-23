package com.finalproj.orbitflow.attendance.leave.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "leave_usage_allocation",
        uniqueConstraints = @UniqueConstraint(name = "uk_leave_alloc_record_grant",
                columnNames = {"attendance_record_id", "grant_history_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveUsageAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "attendance_record_id", nullable = false)
    private Long attendanceRecordId;

    @Column(name = "grant_history_id", nullable = false)
    private Long grantHistoryId;

    @Column(name = "allocated_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal allocatedDays;

    @Column(name = "released_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal releasedDays;

    public static LeaveUsageAllocation create(Long companyId, Long employeeId,
                                              Long recordId, Long grantId,
                                              BigDecimal days) {
        if (days == null || days.signum() <= 0)
            throw new IllegalArgumentException("차감 일수가 올바르지 않습니다.");
        LeaveUsageAllocation a = new LeaveUsageAllocation();
        a.companyId = companyId;
        a.employeeId = employeeId;
        a.attendanceRecordId = recordId;
        a.grantHistoryId = grantId;
        a.allocatedDays = days;
        a.releasedDays = BigDecimal.ZERO;
        return a;
    }

    public BigDecimal getActiveDays() {
        return allocatedDays.subtract(releasedDays);
    }

    public void releaseAll() {
        releasedDays = allocatedDays;
    }

    // 동일 기록 재승인 시 기배분분을 복구 (별도 신규 배분을 만들지 않음)
    public void reactivate(BigDecimal days) {
        if (days.signum() <= 0 || getActiveDays().signum() != 0)
            throw new IllegalStateException("기존 배분이 활성화되어 있습니다.");
        allocatedDays = days;
        releasedDays = BigDecimal.ZERO;
    }
}
