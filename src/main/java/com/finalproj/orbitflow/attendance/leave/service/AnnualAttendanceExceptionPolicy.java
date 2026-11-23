
package com.finalproj.orbitflow.attendance.leave.service;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 연간 출근율 산정 시 법적 예외 기간의 처리 방향을 분류한다.
 *
 * 실제 출근일수 계산 및 연차 부여는 담당하지 않는다.
 *
 * verified는 해당 기간의 종류, 적용 날짜,
 * 관련 증빙 및 승인 상태가 확인되었음을 의미한다.
 */
@Component
public class AnnualAttendanceExceptionPolicy {

    public enum PeriodType {

        // 근로기준법 제60조 제6항에 따른 출근 간주 대상
        WORK_RELATED_INJURY_ABSENCE,
        MATERNITY_LEAVE,
        PARENTAL_LEAVE,
        CHILDCARE_REDUCED_HOURS,
        PREGNANCY_REDUCED_HOURS,

        // 별도 소정근로일수 및 비례 산정 검토 대상
        PERSONAL_LEAVE_OF_ABSENCE,
        LAWFUL_STRIKE,

        // 유형이 확정되지 않은 기간
        UNKNOWN
    }

    public enum Treatment {

        // 소정근로일에 포함하고 출근 인정
        DEEMED_ATTENDED,

        // 제외 기간 및 비례 부여 방식 검토 필요
        EXCLUSION_REVIEW_REQUIRED,

        // 출근율 자동 확정 불가
        UNDETERMINED
    }

    public Treatment classify(
            PeriodType periodType,
            boolean verified
    ) {
        Objects.requireNonNull(
                periodType,
                "예외 기간 유형은 필수입니다."
        );

        // 증빙·기간·적용 자격이 확인되지 않았다면
        // 법정 출근 인정이라고 추정하지 않는다.
        if (!verified) {
            return Treatment.UNDETERMINED;
        }

        return switch (periodType) {

            case WORK_RELATED_INJURY_ABSENCE,
                 MATERNITY_LEAVE,
                 PARENTAL_LEAVE,
                 CHILDCARE_REDUCED_HOURS,
                 PREGNANCY_REDUCED_HOURS ->
                    Treatment.DEEMED_ATTENDED;

            case PERSONAL_LEAVE_OF_ABSENCE,
                 LAWFUL_STRIKE ->
                    Treatment.EXCLUSION_REVIEW_REQUIRED;

            case UNKNOWN ->
                    Treatment.UNDETERMINED;
        };
    }
}
