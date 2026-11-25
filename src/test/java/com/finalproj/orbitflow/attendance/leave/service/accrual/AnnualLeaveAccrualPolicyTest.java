
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class AnnualLeaveAccrualPolicyTest {

    private final AnnualLeaveAccrualPolicy policy =
            new AnnualLeaveAccrualPolicy();

    private AnnualLeaveAccrualPolicy.Decision evaluate(
            int years,
            int scheduled,
            int attended,
            int undetermined
    ) {
        LocalDate hire = LocalDate.of(2020, 3, 10);

        return policy.evaluate(
                hire,
                hire.plusYears(years),
                scheduled,
                attended,
                undetermined
        );
    }

    @Test
    void 입사1주년_출근율80퍼센트이상이면_15일() {
        var result = evaluate(1, 200, 160, 0);

        assertEquals(
                AnnualLeaveAccrualPolicy.Status.ELIGIBLE,
                result.status()
        );
        assertEquals(15, result.grantDays());
    }

    @Test
    void 출근율80퍼센트미만이면_정기연차를_부여하지않는다() {
        var result = evaluate(1, 200, 159, 0);

        assertEquals(
                AnnualLeaveAccrualPolicy.Status.BELOW_80_PERCENT,
                result.status()
        );
        assertEquals(0, result.grantDays());
    }

    @Test
    void 입사2주년은_15일() {
        assertEquals(
                15,
                evaluate(2, 200, 200, 0).grantDays()
        );
    }

    @Test
    void 입사3주년은_16일() {
        assertEquals(
                16,
                evaluate(3, 200, 200, 0).grantDays()
        );
    }

    @Test
    void 입사5주년은_17일() {
        assertEquals(
                17,
                evaluate(5, 200, 200, 0).grantDays()
        );
    }

    @Test
    void 입사21주년은_최대25일() {
        assertEquals(
                25,
                evaluate(21, 200, 200, 0).grantDays()
        );
    }

    @Test
    void 입사30주년도_25일을_초과하지않는다() {
        assertEquals(
                25,
                evaluate(30, 200, 200, 0).grantDays()
        );
    }

    @Test
    void 근태미확정일이_있으면_부여를_보류한다() {
        var result = evaluate(1, 200, 199, 1);

        assertEquals(
                AnnualLeaveAccrualPolicy.Status.UNDETERMINED,
                result.status()
        );
        assertEquals(0, result.grantDays());
    }

    @Test
    void 소정근로일이_없으면_임의로_15일을_지급하지않는다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> evaluate(1, 0, 0, 0)
        );
    }

    @Test
    void 입사기념일이_아니면_정기연차를_지급할수없다() {
        LocalDate hire = LocalDate.of(2025, 3, 10);

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.evaluate(
                        hire,
                        LocalDate.of(2026, 3, 9),
                        200,
                        200,
                        0
                )
        );
    }

    @Test
    void 입사일이_2월29일이어도_1주년을_판정한다() {
        LocalDate hire = LocalDate.of(2024, 2, 29);

        var result = policy.evaluate(
                hire,
                LocalDate.of(2025, 2, 28),
                200,
                200,
                0
        );

        assertEquals(15, result.grantDays());
    }
}
