package com.finalproj.orbitflow.attendance.rule.controller;

import com.finalproj.orbitflow.attendance.rule.dto.request.DefaultRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.service.AttendanceRuleService;
import com.finalproj.orbitflow.global.exception.GlobalExceptionHandler;
import com.finalproj.orbitflow.global.exception.InvalidRequestException;

import com.finalproj.orbitflow.global.security.SecurityUser;
import com.finalproj.orbitflow.hr.company.service.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.context.request.NativeWebRequest;

import java.time.LocalTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttendanceRuleControllerTest {

    private MockMvc mockMvc;
    private AttendanceRuleService attendanceRuleService;

    @BeforeEach
    void setUp() {
        attendanceRuleService = mock(AttendanceRuleService.class);

        AttendanceRuleController controller =
                new AttendanceRuleController(attendanceRuleService);

        SecurityUser admin = mock(SecurityUser.class);
        when(admin.getCompanyId()).thenReturn(1L);

        HandlerMethodArgumentResolver principalResolver =
                new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(
                                AuthenticationPrincipal.class
                        ) && SecurityUser.class.isAssignableFrom(
                                parameter.getParameterType()
                        );
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer mavContainer,
                            NativeWebRequest webRequest,
                            org.springframework.web.bind.support.WebDataBinderFactory binderFactory
                    ) {
                        return admin;
                    }
                };

        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver)
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("정상적인 기본 규칙 수정 요청은 200 OK를 반환한다")
    void updateDefaultRule_success() throws Exception {
        mockMvc.perform(
                put("/api/admin/rules/default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultStartTime": "08:00:00",
                                  "defaultEndTime": "17:00:00",
                                  "defaultBreakMinutes": 60
                                }
                                """)
        ).andExpect(status().isOk());

        verify(attendanceRuleService).updateDefaultRule(
                eq(1L),
                any(DefaultRuleUpdateReqDto.class)
        );
    }

    @Test
    @DisplayName("잘못된 근무 시간은 400 Bad Request를 반환한다")
    void updateDefaultRule_invalidWorkTime() throws Exception {
        when(attendanceRuleService.updateDefaultRule(
                eq(1L),
                any(DefaultRuleUpdateReqDto.class)
        )).thenThrow(
                new InvalidRequestException(
                        "퇴근 시간은 출근 시간보다 이후여야 합니다."
                )
        );

        mockMvc.perform(
                        put("/api/admin/rules/default")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "defaultStartTime": "19:00:00",
                                  "defaultEndTime": "18:00:00",
                                  "defaultBreakMinutes": 60
                                }
                                """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("퇴근 시간은 출근 시간보다 이후여야 합니다."));
    }

    @Test
    @DisplayName("휴게 시간이 480분을 초과하면 400 Bad Request를 반환한다")
    void updateDefaultRule_breakMinutesTooLarge() throws Exception {
        mockMvc.perform(
                        put("/api/admin/rules/default")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "defaultStartTime": "09:00:00",
                                  "defaultEndTime": "18:00:00",
                                  "defaultBreakMinutes": 481
                                }
                                """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(attendanceRuleService);
    }

    @Test
    @DisplayName("휴게 시간이 음수이면 400 Bad Request를 반환한다")
    void updateDefaultRule_negativeBreakMinutes() throws Exception {
        mockMvc.perform(
                        put("/api/admin/rules/default")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "defaultStartTime": "09:00:00",
                                  "defaultEndTime": "18:00:00",
                                  "defaultBreakMinutes": -1
                                }
                                """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(attendanceRuleService);
    }
}