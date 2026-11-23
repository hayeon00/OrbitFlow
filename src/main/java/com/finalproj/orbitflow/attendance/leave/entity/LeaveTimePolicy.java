
package com.finalproj.orbitflow.attendance.leave.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Entity
@Table(
        name = "leave_time_policy",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_leave_time_policy_company",
                columnNames = "company_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveTimePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "morning_half_return_time", nullable = false)
    private LocalTime morningHalfReturnTime;

    @Column(name = "afternoon_half_leave_time", nullable = false)
    private LocalTime afternoonHalfLeaveTime;

    public static LeaveTimePolicy create(
            Long companyId,
            LocalTime morningHalfReturnTime,
            LocalTime afternoonHalfLeaveTime
    ) {
        LeaveTimePolicy policy = new LeaveTimePolicy();

        policy.companyId = companyId;

        policy.changeTimes(
                morningHalfReturnTime,
                afternoonHalfLeaveTime
        );

        return policy;
    }

    public void changeTimes(
            LocalTime morningHalfReturnTime,
            LocalTime afternoonHalfLeaveTime
    ) {
        if (morningHalfReturnTime == null
                || afternoonHalfLeaveTime == null) {
            throw new IllegalArgumentException(
                    "오전·오후 반차 시간은 필수입니다."
            );
        }

        this.morningHalfReturnTime =
                morningHalfReturnTime;

        this.afternoonHalfLeaveTime =
                afternoonHalfLeaveTime;
    }
}
