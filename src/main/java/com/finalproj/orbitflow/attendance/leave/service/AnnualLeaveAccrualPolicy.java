
package com.finalproj.orbitflow.attendance.leave.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Objects;

@Component
public class AnnualLeaveAccrualPolicy {

    public enum Status {
        ELIGIBLE,
        BELOW_80_PERCENT,
        UNDETERMINED
    }

    public record Decision(
            Status status,
            int yearsOfService,
            int scheduledDays,
            int attendedDays,
            int undeterminedDays,
            int grantDays
    ) {
    }

    /**
     * 입사일 기준 정기 연차 부여 여부와 일수를 계산한다.
     *
     * scheduledDays:
     *   연간 소정근로일 수
     *
     * attendedDays:
     *   실제 출근일 + 법적으로 출근 인정되는 날
     *
     * undeterminedDays:
     *   근태 기록 누락, 승인 충돌 등 판정 미확정일
     *
     * 근태 기록 조회와 일자별 분류는 별도 서비스의 책임이다.
     */
    public Decision evaluate(
            LocalDate hireDate,
            LocalDate grantDate,
            int scheduledDays,
            int attendedDays,
            int undeterminedDays
    ) {
        Objects.requireNonNull(hireDate, "입사일은 필수입니다.");
        Objects.requireNonNull(grantDate, "연차 발생일은 필수입니다.");

        int yearsOfService =
                grantDate.getYear() - hireDate.getYear();

        // 정확한 입사 기념일만 정기 연차 발생일로 인정
        if (yearsOfService < 1
                || !hireDate.plusYears(yearsOfService)
                .equals(grantDate)) {
            throw new IllegalArgumentException(
                    "정기 연차 발생일은 입사 1주년 이후의 "
                            + "정확한 입사 기념일이어야 합니다."
            );
        }

        if (scheduledDays <= 0
                || attendedDays < 0
                || undeterminedDays < 0
                || (long) attendedDays + undeterminedDays
                > scheduledDays) {
            throw new IllegalArgumentException(
                    "연간 근태 일수 데이터가 올바르지 않습니다."
            );
        }

        // 미확정 근태가 있으면 임의로 연차를 지급하지 않는다.
        if (undeterminedDays > 0) {
            return new Decision(
                    Status.UNDETERMINED,
                    yearsOfService,
                    scheduledDays,
                    attendedDays,
                    undeterminedDays,
                    0
            );
        }

        // 소수점 반올림 없이 정확하게 80% 비교
        boolean atLeast80Percent =
                (long) attendedDays * 100
                        >= (long) scheduledDays * 80;

        if (!atLeast80Percent) {
            return new Decision(
                    Status.BELOW_80_PERCENT,
                    yearsOfService,
                    scheduledDays,
                    attendedDays,
                    0,
                    0
            );
        }

        int additionalDays =
                (yearsOfService - 1) / 2;

        int grantDays =
                Math.min(15 + additionalDays, 25);

        return new Decision(
                Status.ELIGIBLE,
                yearsOfService,
                scheduledDays,
                attendedDays,
                0,
                grantDays
        );
    }
}
