package com.finalproj.orbitflow.attendance.leave.entity;

import com.finalproj.orbitflow.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "grant_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_leave_grant_event",
                columnNames = {"employee_id", "grant_type", "grant_date"}
        )
)
public class LeaveGrant extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "grant_date", nullable = false)
    private LocalDate grantDate;

    @Column(name = "granted_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal grantedDays;

    @Column(name = "remaining_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal remainingDays;

    @Column(name = "grant_type", nullable = false, length = 50)
    private String grantType;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    @Builder.Default
    @Column(name = "is_expired", nullable = false)
    private Boolean isExpired = false;

    public static LeaveGrant issue(
            Long companyId, Long employeeId, LocalDate grantDate,
            BigDecimal days, String grantType, LocalDate expirationDate) {
        if (companyId == null || employeeId == null || grantDate == null
                || days == null || days.signum() <= 0
                || grantType == null || grantType.isBlank()
                || expirationDate == null || expirationDate.isBefore(grantDate)) {
            throw new IllegalArgumentException("연차 부여 정보가 올바르지 않습니다.");
        }
        return LeaveGrant.builder()
                .companyId(companyId)
                .employeeId(employeeId)
                .grantDate(grantDate)
                .grantedDays(days)
                .remainingDays(days)
                .grantType(grantType)
                .expirationDate(expirationDate)
                .isExpired(false)
                .build();
    }

    public void deduct(BigDecimal days) {
        requirePositive(days);
        if (Boolean.TRUE.equals(isExpired)) {
            throw new IllegalStateException("만료된 연차는 사용할 수 없습니다.");
        }
        if (remainingDays.compareTo(days) < 0) {
            throw new IllegalStateException("부여 건의 잔여 연차가 부족합니다.");
        }
        remainingDays = remainingDays.subtract(days);
    }

    public void restore(BigDecimal days) {
        requirePositive(days);
        if (Boolean.TRUE.equals(isExpired)) {
            throw new IllegalStateException("만료된 연차는 일반 복구할 수 없습니다.");
        }
        BigDecimal result = remainingDays.add(days);
        if (result.compareTo(grantedDays) > 0) {
            throw new IllegalStateException("부여 일수를 초과하여 복구할 수 없습니다.");
        }
        remainingDays = result;
    }

    public void expire() {
        remainingDays = BigDecimal.ZERO;
        isExpired = true;
    }

    public void updateExpiredStatus(Boolean expired) {
        if (Boolean.TRUE.equals(expired)) {
            expire();
        } else {
            throw new IllegalStateException("소멸 취소는 별도 정산 절차가 필요합니다.");
        }
    }

    private static void requirePositive(BigDecimal days) {
        if (days == null || days.signum() <= 0) {
            throw new IllegalArgumentException("일수는 0보다 커야 합니다.");
        }
    }
}
