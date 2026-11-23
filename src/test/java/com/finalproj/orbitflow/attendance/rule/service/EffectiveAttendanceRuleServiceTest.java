
package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;
import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.exception.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EffectiveAttendanceRuleServiceTest {

    @Mock
    private AttendanceRuleRepository attendanceRuleRepository;

    @Mock
    private EmployeeRuleRepository employeeRuleRepository;

    @InjectMocks
    private EffectiveAttendanceRuleService service;

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate WORK_DATE =
            LocalDate.of(2026, 11, 2);

    private AttendanceRule companyRule;

    @BeforeEach
    void setUp() {
        companyRule = AttendanceRule.builder()
                .id(100L)
                .companyId(COMPANY_ID)
                .name("표준 근무 규칙")
                .defaultStartTime(LocalTime.of(9, 0))
                .defaultEndTime(LocalTime.of(18, 0))
                .breakStartTime(LocalTime.of(12, 0))
                .breakEndTime(LocalTime.of(13, 0))
                .defaultBreakMinutes(60)
                .isDefault(true)
                .build();
    }

    private void stubCompanyRule() {
        when(attendanceRuleRepository
                .findByCompanyIdAndIsDefaultTrue(COMPANY_ID))
                .thenReturn(Optional.of(companyRule));
    }

    private void stubEmployeeRules(List<EmployeeRule> rules) {
        when(employeeRuleRepository.findEffectiveRules(
                COMPANY_ID, EMPLOYEE_ID, WORK_DATE
        )).thenReturn(rules);
    }

    private EmployeeRule mockEmployeeRule(
            LocalTime start,
            LocalTime end,
            LocalTime breakStart,
            LocalTime breakEnd,
            Integer breakMinutes
    ) {
        EmployeeRule rule = mock(EmployeeRule.class);
        lenient().when(rule.getId()).thenReturn(200L);

        when(rule.getStartTime()).thenReturn(start);
        when(rule.getEndTime()).thenReturn(end);
        when(rule.getBreakStartTime()).thenReturn(breakStart);
        when(rule.getBreakEndTime()).thenReturn(breakEnd);
        when(rule.getBreakMinutes()).thenReturn(breakMinutes);

        return rule;
    }

    @Test
    void 예외규칙이_없으면_회사_기본규칙을_적용한다() {
        stubCompanyRule();
        stubEmployeeRules(List.of());

        EffectiveAttendanceRule result =
                service.resolve(COMPANY_ID, EMPLOYEE_ID, WORK_DATE);

        assertFalse(result.employeeOverride());
        assertEquals(100L, result.appliedRuleId());
        assertEquals(LocalTime.of(9, 0), result.workStartTime());
        assertEquals(LocalTime.of(18, 0), result.workEndTime());
        assertEquals(LocalTime.of(12, 0), result.breakStartTime());
        assertEquals(LocalTime.of(13, 0), result.breakEndTime());
        assertEquals(60, result.breakMinutes());
    }

    @Test
    void 사원별_예외규칙이_있으면_우선적용한다() {
        stubCompanyRule();

        EmployeeRule employeeRule = mockEmployeeRule(
                LocalTime.of(10, 0),
                LocalTime.of(19, 0),
                LocalTime.of(13, 0),
                LocalTime.of(14, 0),
                60
        );

        stubEmployeeRules(List.of(employeeRule));

        EffectiveAttendanceRule result =
                service.resolve(COMPANY_ID, EMPLOYEE_ID, WORK_DATE);

        assertTrue(result.employeeOverride());
        assertEquals(200L, result.appliedRuleId());
        assertEquals(LocalTime.of(10, 0), result.workStartTime());
        assertEquals(LocalTime.of(19, 0), result.workEndTime());
        assertEquals(LocalTime.of(13, 0), result.breakStartTime());
        assertEquals(LocalTime.of(14, 0), result.breakEndTime());
        assertEquals(60, result.breakMinutes());
    }

    @Test
    void 사원점심시간이_없으면_회사점심시간을_상속한다() {
        stubCompanyRule();

        EmployeeRule employeeRule = mockEmployeeRule(
                LocalTime.of(10, 0),
                LocalTime.of(19, 0),
                null,
                null,
                null
        );

        stubEmployeeRules(List.of(employeeRule));

        EffectiveAttendanceRule result =
                service.resolve(COMPANY_ID, EMPLOYEE_ID, WORK_DATE);

        assertTrue(result.employeeOverride());
        assertEquals(LocalTime.of(12, 0), result.breakStartTime());
        assertEquals(LocalTime.of(13, 0), result.breakEndTime());
        assertEquals(60, result.breakMinutes());
    }

    @Test
    void 회사점심시간이_사원근무시간_밖이면_예외발생() {
        stubCompanyRule();

        EmployeeRule employeeRule = mockEmployeeRule(
                LocalTime.of(13, 0),
                LocalTime.of(22, 0),
                null,
                null,
                null
        );

        stubEmployeeRules(List.of(employeeRule));

        assertThrows(
                BusinessException.class,
                () -> service.resolve(
                        COMPANY_ID, EMPLOYEE_ID, WORK_DATE
                )
        );
    }

    @Test
    void 점심시간과_휴게분수가_불일치하면_예외발생() {
        stubCompanyRule();

        EmployeeRule employeeRule = mockEmployeeRule(
                LocalTime.of(10, 0),
                LocalTime.of(19, 0),
                LocalTime.of(13, 0),
                LocalTime.of(14, 0),
                90
        );

        stubEmployeeRules(List.of(employeeRule));

        assertThrows(
                BusinessException.class,
                () -> service.resolve(
                        COMPANY_ID, EMPLOYEE_ID, WORK_DATE
                )
        );
    }

    @Test
    void 같은날_적용되는_예외규칙이_여러개면_예외발생() {
        stubCompanyRule();

        EmployeeRule first = mock(EmployeeRule.class);
        EmployeeRule second = mock(EmployeeRule.class);

        stubEmployeeRules(List.of(first, second));

        assertThrows(
                BusinessException.class,
                () -> service.resolve(
                        COMPANY_ID, EMPLOYEE_ID, WORK_DATE
                )
        );
    }

    @Test
    void 회사_기본규칙이_없으면_예외발생() {
        when(attendanceRuleRepository
                .findByCompanyIdAndIsDefaultTrue(COMPANY_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                BusinessException.class,
                () -> service.resolve(
                        COMPANY_ID, EMPLOYEE_ID, WORK_DATE
                )
        );

        verifyNoInteractions(employeeRuleRepository);
    }

    @Test
    void 회사ID가_null이면_예외발생() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolve(
                        null, EMPLOYEE_ID, WORK_DATE
                )
        );

        verifyNoInteractions(
                attendanceRuleRepository,
                employeeRuleRepository
        );
    }

    @Test
    void 사원ID가_null이면_예외발생() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolve(
                        COMPANY_ID, null, WORK_DATE
                )
        );
    }

    @Test
    void 근무날짜가_null이면_예외발생() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolve(
                        COMPANY_ID, EMPLOYEE_ID, null
                )
        );
    }
}
