package com.finalproj.orbitflow.attendance.rule.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;

@DisplayName("회사 기본 근태 규칙 수정 단위 테스트")
class AttendanceRuleTest {

    private AttendanceRule rule;

    @BeforeEach
    void setUp() {
        rule = AttendanceRule.builder()
                .companyId(1L)
                .name("기본 근태 규칙")
                .defaultStartTime(LocalTime.of(9, 0))
                .defaultEndTime(LocalTime.of(18, 0))
                .defaultBreakMinutes(60)
                .isDefault(true)
                .build();
    }

    @Test
    @DisplayName("출근 시간만 수정하면 기존 퇴근 시간은 유지된다")
    void updateStartTimeOnly() {
        // when
        rule.updateRule(
                LocalTime.of(10, 0),
                null,
                null
        );

        // then
        assertThat(rule.getDefaultStartTime())
                .isEqualTo(LocalTime.of(10, 0));

        assertThat(rule.getDefaultEndTime())
                .isEqualTo(LocalTime.of(18, 0));

        assertThat(rule.getDefaultBreakMinutes())
                .isEqualTo(60);
    }

    @Test
    @DisplayName("퇴근 시간만 수정하면 기존 출근 시간은 유지된다")
    void updateEndTimeOnly() {
        // when
        rule.updateRule(
                null,
                LocalTime.of(17, 0),
                null
        );

        // then
        assertThat(rule.getDefaultStartTime())
                .isEqualTo(LocalTime.of(9, 0));

        assertThat(rule.getDefaultEndTime())
                .isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("출근 시간을 기존 퇴근 시간보다 늦게 변경하면 거부한다")
    void rejectInvalidStartTime() {
        // when & then
        assertThatThrownBy(() ->
                rule.updateRule(
                        LocalTime.of(19, 0),
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "퇴근 시간은 출근 시간보다 이후"
                );

        // 기존 값 유지 확인
        assertThat(rule.getDefaultStartTime())
                .isEqualTo(LocalTime.of(9, 0));

        assertThat(rule.getDefaultEndTime())
                .isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    @DisplayName("퇴근 시간을 기존 출근 시간보다 빠르게 변경하면 거부한다")
    void rejectInvalidEndTime() {
        assertThatThrownBy(() ->
                rule.updateRule(
                        null,
                        LocalTime.of(8, 0),
                        null
                )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(rule.getDefaultStartTime())
                .isEqualTo(LocalTime.of(9, 0));

        assertThat(rule.getDefaultEndTime())
                .isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    @DisplayName("출근 시간과 퇴근 시간이 같으면 거부한다")
    void rejectSameStartAndEndTime() {
        assertThatThrownBy(() ->
                rule.updateRule(
                        LocalTime.of(10, 0),
                        LocalTime.of(10, 0),
                        null
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("휴게 시간이 범위를 벗어나면 거부하고 기존 값은 유지한다")
    void rejectInvalidBreakMinutes() {
        assertThatThrownBy(() ->
                rule.updateRule(
                        LocalTime.of(10, 0),
                        LocalTime.of(19, 0),
                        500
                )
        ).isInstanceOf(IllegalArgumentException.class);

        // 유효한 시간도 예외 발생 시 반영되지 않아야 한다.
        assertThat(rule.getDefaultStartTime())
                .isEqualTo(LocalTime.of(9, 0));

        assertThat(rule.getDefaultEndTime())
                .isEqualTo(LocalTime.of(18, 0));

        assertThat(rule.getDefaultBreakMinutes())
                .isEqualTo(60);
    }

    @Test
    @DisplayName("휴게 시간을 0분으로 변경할 수 있다")
    void allowZeroBreakMinutes() {
        rule.updateRule(null, null, 0);

        assertThat(rule.getDefaultBreakMinutes())
                .isZero();
    }

    @Test
    @DisplayName("모든 값을 정상적으로 수정할 수 있다")
    void updateAllFields() {
        rule.updateRule(
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                30
        );

        assertThat(rule.getDefaultStartTime())
                .isEqualTo(LocalTime.of(8, 0));

        assertThat(rule.getDefaultEndTime())
                .isEqualTo(LocalTime.of(17, 0));

        assertThat(rule.getDefaultBreakMinutes())
                .isEqualTo(30);

        assertThat(rule.getUpdatedAt())
                .isNotNull();
    }
}