
package com.finalproj.orbitflow.attendance.rule.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;

class EmployeeRuleTest {

    @Test
    @DisplayName("정상적인 예외 근태 규칙을 생성할 수 있다")
    void createRuleSuccess() {
        // given
        LocalTime startTime = LocalTime.of(9, 0);
        LocalTime endTime = LocalTime.of(18, 0);

        // when
        EmployeeRule rule = EmployeeRule.create(
                1L,
                100L,
                startTime,
                endTime,
                60,
                "유연근무",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31)
        );

        // then
        assertThat(rule.getCompanyId()).isEqualTo(1L);
        assertThat(rule.getEmployeeId()).isEqualTo(100L);
        assertThat(rule.getStartTime()).isEqualTo(startTime);
        assertThat(rule.getEndTime()).isEqualTo(endTime);
        assertThat(rule.getIsActive()).isTrue();
        assertThat(rule.getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("종료일이 시작일보다 빠르면 생성할 수 없다")
    void createRuleInvalidDate() {
        assertThatThrownBy(() -> EmployeeRule.create(
                1L,
                100L,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                60,
                "유연근무",
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 10)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("퇴근 시간이 출근 시간보다 빠르면 생성할 수 없다")
    void createRuleInvalidTime() {
        assertThatThrownBy(() -> EmployeeRule.create(
                1L,
                100L,
                LocalTime.of(18, 0),
                LocalTime.of(9, 0),
                60,
                "유연근무",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("휴게 시간이 허용 범위를 벗어나면 생성할 수 없다")
    void createRuleInvalidBreakMinutes() {
        assertThatThrownBy(() -> EmployeeRule.create(
                1L,
                100L,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                500,
                "유연근무",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("규칙 삭제 시 비활성화된다")
    void deleteRule() {
        // given
        EmployeeRule rule = EmployeeRule.create(
                1L,
                100L,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                60,
                "유연근무",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31)
        );

        // when
        rule.delete();

        // then
        assertThat(rule.getIsActive()).isFalse();
    }
}
