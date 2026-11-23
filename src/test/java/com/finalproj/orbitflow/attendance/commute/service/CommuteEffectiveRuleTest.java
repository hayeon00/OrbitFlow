
package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.attendance.commute.dto.ActiveRuleResDto;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
import com.finalproj.orbitflow.global.exception.BusinessException;
import com.finalproj.orbitflow.global.time.attendance.TimeProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommuteEffectiveRuleTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate WORK_DATE =
            LocalDate.of(2026, 11, 3);

    @Mock
    private EffectiveAttendanceRuleService effectiveAttendanceRuleService;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private CommuteService commuteService;

    private EffectiveAttendanceRule rule(
            int startHour,
            int endHour,
            boolean employeeOverride
    ) {

        return new EffectiveAttendanceRule(
                COMPANY_ID,
                EMPLOYEE_ID,
                WORK_DATE,
                LocalTime.of(startHour, 0),
                LocalTime.of(endHour, 0),
                LocalTime.of(12, 0),
                LocalTime.of(13, 0),
                60,
                employeeOverride,
                employeeOverride ? 200L : 100L
        );
    }

    @Test
    void 직원별_근무규칙이_있으면_10시부터_19시를_적용한다() {

        when(timeProvider.today())
                .thenReturn(WORK_DATE);

        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID,
                EMPLOYEE_ID,
                WORK_DATE
        )).thenReturn(rule(10, 19, true));

        ActiveRuleResDto result =
                commuteService.getActiveRule(
                        COMPANY_ID,
                        EMPLOYEE_ID
                );

        assertEquals(
                LocalTime.of(10, 0),
                result.getStartTime()
        );

        assertEquals(
                LocalTime.of(19, 0),
                result.getEndTime()
        );

        assertEquals("EXCEPTION", result.getRuleType());

        verify(effectiveAttendanceRuleService)
                .resolve(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        WORK_DATE
                );
    }

    @Test
    void 직원별_규칙이_없으면_회사기본_9시부터_18시를_적용한다() {

        when(timeProvider.today())
                .thenReturn(WORK_DATE);

        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID,
                EMPLOYEE_ID,
                WORK_DATE
        )).thenReturn(rule(9, 18, false));

        ActiveRuleResDto result =
                commuteService.getActiveRule(
                        COMPANY_ID,
                        EMPLOYEE_ID
                );

        assertEquals(
                LocalTime.of(9, 0),
                result.getStartTime()
        );

        assertEquals(
                LocalTime.of(18, 0),
                result.getEndTime()
        );

        assertEquals("DEFAULT", result.getRuleType());
    }

    @Test
    void 적용규칙이_없으면_임의근무시간을_사용하지_않는다() {

        when(timeProvider.today())
                .thenReturn(WORK_DATE);

        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID,
                EMPLOYEE_ID,
                WORK_DATE
        )).thenThrow(
                new BusinessException(
                        "회사 기본 근태 규칙이 없습니다."
                )
        );

        assertThrows(
                BusinessException.class,
                () -> commuteService.getActiveRule(
                        COMPANY_ID,
                        EMPLOYEE_ID
                )
        );
    }

    @Test
    void 중복된_직원별규칙도_임의선택하지_않는다() {

        when(timeProvider.today())
                .thenReturn(WORK_DATE);

        when(effectiveAttendanceRuleService.resolve(
                COMPANY_ID,
                EMPLOYEE_ID,
                WORK_DATE
        )).thenThrow(
                new BusinessException(
                        "해당 날짜에 적용되는 사원별 예외 규칙이 "
                                + "두 개 이상 존재합니다."
                )
        );

        assertThrows(
                BusinessException.class,
                () -> commuteService.getActiveRule(
                        COMPANY_ID,
                        EMPLOYEE_ID
                )
        );
    }
}
