
package com.finalproj.orbitflow.attendance.leave.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static com.finalproj.orbitflow.attendance.leave.service
        .AnnualAttendanceExceptionPolicy.PeriodType.*;
import static com.finalproj.orbitflow.attendance.leave.service
        .AnnualAttendanceExceptionPolicy.Treatment.*;

class AnnualAttendanceExceptionPolicyTest {

    private final AnnualAttendanceExceptionPolicy policy =
            new AnnualAttendanceExceptionPolicy();

    @Test
    void 업무상_부상질병_휴업은_출근으로_간주한다() {
        assertEquals(
                DEEMED_ATTENDED,
                policy.classify(
                        WORK_RELATED_INJURY_ABSENCE,
                        true
                )
        );
    }

    @Test
    void 출산전후휴가는_출근으로_간주한다() {
        assertEquals(
                DEEMED_ATTENDED,
                policy.classify(
                        MATERNITY_LEAVE,
                        true
                )
        );
    }

    @Test
    void 법정_육아휴직은_출근으로_간주한다() {
        assertEquals(
                DEEMED_ATTENDED,
                policy.classify(
                        PARENTAL_LEAVE,
                        true
                )
        );
    }

    @Test
    void 육아기_근로시간단축은_출근인정대상이다() {
        assertEquals(
                DEEMED_ATTENDED,
                policy.classify(
                        CHILDCARE_REDUCED_HOURS,
                        true
                )
        );
    }

    @Test
    void 임신기_근로시간단축은_출근인정대상이다() {
        assertEquals(
                DEEMED_ATTENDED,
                policy.classify(
                        PREGNANCY_REDUCED_HOURS,
                        true
                )
        );
    }

    @Test
    void 개인사유_휴직은_소정근로일수_제외여부를_검토한다() {
        assertEquals(
                EXCLUSION_REVIEW_REQUIRED,
                policy.classify(
                        PERSONAL_LEAVE_OF_ABSENCE,
                        true
                )
        );
    }

    @Test
    void 적법한_쟁의행위는_별도_산정이_필요하다() {
        assertEquals(
                EXCLUSION_REVIEW_REQUIRED,
                policy.classify(
                        LAWFUL_STRIKE,
                        true
                )
        );
    }

    @Test
    void 확인되지_않은_기간은_출근인정하지_않는다() {
        assertEquals(
                UNDETERMINED,
                policy.classify(
                        PARENTAL_LEAVE,
                        false
                )
        );
    }

    @Test
    void 알수없는_휴직유형은_미확정으로_처리한다() {
        assertEquals(
                UNDETERMINED,
                policy.classify(
                        UNKNOWN,
                        true
                )
        );
    }

    @Test
    void 예외기간_유형이_null이면_오류를_발생시킨다() {
        assertThrows(
                NullPointerException.class,
                () -> policy.classify(null, true)
        );
    }
}
