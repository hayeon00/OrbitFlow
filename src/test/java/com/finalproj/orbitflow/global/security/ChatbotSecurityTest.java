package com.finalproj.orbitflow.global.security;

import com.finalproj.orbitflow.auth.repository.RefreshTokenRepository;
import com.finalproj.orbitflow.chatbot.chatbot.controller.ChatController;
import com.finalproj.orbitflow.chatbot.chatbot.service.ChatService;
import com.finalproj.orbitflow.global.security.jwt.JwtAuthenticationFilter;
import com.finalproj.orbitflow.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 SecurityConfig + JwtAuthenticationFilter 로 챗봇 API 의 인증 강제 여부를 검증한다.
 * (챗봇 API 경로 /api/auth/chatbot/** 는 permitAll 대상인 /api/auth/** 아래에 있다)
 */
@SpringJUnitWebConfig(ChatbotSecurityTest.TestConfig.class)
class ChatbotSecurityTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, ChatController.class})
    static class TestConfig {
        @Bean JwtProvider jwtProvider() { return Mockito.mock(JwtProvider.class); }
        @Bean CustomUserDetailsService customUserDetailsService() { return Mockito.mock(CustomUserDetailsService.class); }
        @Bean RefreshTokenRepository refreshTokenRepository() { return Mockito.mock(RefreshTokenRepository.class); }
        @Bean ChatService chatService() { return Mockito.mock(ChatService.class); }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtProvider p, CustomUserDetailsService u, RefreshTokenRepository r) {
            return new JwtAuthenticationFilter(p, u, r);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired ChatService chatService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(chatService);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 토큰_없이_챗봇_질문을_보내면_차단되고_서비스가_호출되지_않는다() throws Exception {
        mvc.perform(post("/api/auth/chatbot/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"연차 이월?\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/auth/chatbot/conversations"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(chatService);
    }

    @Test
    void 인증된_사용자는_자신의_회사_범위로_챗봇을_사용한다() throws Exception {
        SecurityUser user = mock(SecurityUser.class);
        when(user.getCompanyId()).thenReturn(1L);
        when(user.getEmployeeId()).thenReturn(10L);
        doReturn(List.of()).when(user).getAuthorities();
        when(chatService.listConversations(any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/auth/chatbot/conversations")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()))))
                .andExpect(status().isOk());

        verify(chatService).listConversations(1L, 10L);
    }
}
