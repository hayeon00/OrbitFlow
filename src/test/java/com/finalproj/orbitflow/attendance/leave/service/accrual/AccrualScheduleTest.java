
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class AccrualScheduleTest {

    @Test
    void 가장_최근_정기연차_발생일() {

        LocalDate hire = LocalDate.of(2024, 11, 5);

        assertEquals(
                LocalDate.of(2026, 11, 5),
                AccrualSchedule.latestRegularGrantDate(
                        hire, LocalDate.of(2026, 11, 5)));

        assertEquals(
                LocalDate.of(2026, 11, 5),
                AccrualSchedule.latestRegularGrantDate(
                        hire, LocalDate.of(2027, 11, 4)));

        assertEquals(
                LocalDate.of(2025, 11, 5),
                AccrualSchedule.latestRegularGrantDate(
                        hire, LocalDate.of(2026, 11, 4)));

        assertNull(
                AccrualSchedule.latestRegularGrantDate(
                        hire, LocalDate.of(2025, 11, 4)));

        // 2월 29일 입사자: 평년에는 2월 28일이 기념일
        LocalDate leapHire = LocalDate.of(2024, 2, 29);

        assertEquals(
                LocalDate.of(2025, 2, 28),
                AccrualSchedule.latestRegularGrantDate(
                        leapHire, LocalDate.of(2025, 2, 28)));

        assertEquals(
                LocalDate.of(2027, 2, 28),
                AccrualSchedule.latestRegularGrantDate(
                        leapHire, LocalDate.of(2028, 2, 28)));

        assertEquals(
                LocalDate.of(2028, 2, 29),
                AccrualSchedule.latestRegularGrantDate(
                        leapHire, LocalDate.of(2028, 2, 29)));
    }

    @Test
    void 정기연차_발생일은_1주년_이후_정확한_입사기념일만_인정() {

        LocalDate hire = LocalDate.of(2024, 11, 5);

        assertTrue(AccrualSchedule.isRegularGrantDate(
                hire, LocalDate.of(2025, 11, 5)));
        assertFalse(AccrualSchedule.isRegularGrantDate(
                hire, LocalDate.of(2025, 11, 6)));
        assertFalse(AccrualSchedule.isRegularGrantDate(
                hire, hire));
        assertFalse(AccrualSchedule.isRegularGrantDate(
                null, LocalDate.of(2025, 11, 5)));
    }

    @Test
    void 입사_1년차_범위() {

        LocalDate hire = LocalDate.of(2026, 1, 31);

        assertTrue(AccrualSchedule.isInFirstYear(hire, hire));
        assertTrue(AccrualSchedule.isInFirstYear(
                hire, LocalDate.of(2027, 1, 30)));
        assertFalse(AccrualSchedule.isInFirstYear(
                hire, LocalDate.of(2027, 1, 31)));
        assertFalse(AccrualSchedule.isInFirstYear(
                hire, LocalDate.of(2026, 1, 30)));
        assertFalse(AccrualSchedule.isInFirstYear(
                null, hire));
    }

    @Test
    void 월별_발생일과_평가기간() {

        LocalDate hire = LocalDate.of(2026, 1, 31);

        // 말일 입사: 다음 달 말일로 보정된다.
        assertEquals(LocalDate.of(2026, 2, 28),
                AccrualSchedule.monthlyGrantDate(hire, 1));
        assertEquals(hire,
                AccrualSchedule.monthlyPeriodStart(hire, 1));
        assertEquals(LocalDate.of(2026, 12, 31),
                AccrualSchedule.monthlyGrantDate(hire, 11));

        assertThrows(IllegalArgumentException.class,
                () -> AccrualSchedule.monthlyGrantDate(hire, 12));
    }
}
