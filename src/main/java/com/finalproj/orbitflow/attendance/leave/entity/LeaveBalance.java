
package com.finalproj.orbitflow.attendance.leave.entity;

import com.finalproj.orbitflow.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "leave_balance",
        uniqueConstraints = {
                @UniqueConstraint(
                        columnNames = {
                                "company_id",
                                "employee_id",
                                "year"
                        }
                )
        }
)
public class LeaveBalance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(
            name = "total_granted",
            nullable = false,
            precision = 7,
            scale = 2
    )
    private BigDecimal totalGranted;

    @Column(
            name = "remaining_days",
            nullable = false,
            precision = 7,
            scale = 2
    )
    private BigDecimal remainingDays;

    public void updateBalance(BigDecimal days) {

        if (this.totalGranted == null) {
            this.totalGranted = BigDecimal.ZERO;
        }

        if (this.remainingDays == null) {
            this.remainingDays = BigDecimal.ZERO;
        }

        this.totalGranted = this.totalGranted.add(days);
        this.remainingDays = this.remainingDays.add(days);
    }

    public void updateBalanceFromActualUsage(
            BigDecimal actualUsedDays
    ) {
        if (this.totalGranted == null) {
            this.totalGranted = BigDecimal.ZERO;
        }

        this.remainingDays =
                this.totalGranted.subtract(actualUsedDays);
    }

    public void deductBalance(BigDecimal deductedDays) {

        if (this.remainingDays == null) {
            this.remainingDays = BigDecimal.ZERO;
        }

        this.remainingDays =
                this.remainingDays.subtract(deductedDays);
    }

    public void changeRemaining(BigDecimal delta) {

        if (delta == null || remainingDays == null) {
            throw new IllegalArgumentException(
                    "잔여량 변경 값이 잘못되었습니다."
            );
        }

        BigDecimal next = remainingDays.add(delta);

        if (next.signum() < 0
                || next.compareTo(totalGranted) > 0) {
            throw new IllegalStateException(
                    "연도별 잔여 연차 범위를 벗어났습니다."
            );
        }

        remainingDays = next;
    }
}
