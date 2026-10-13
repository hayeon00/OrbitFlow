package com.finalproj.orbitflow.attendance.rule.listener;

import com.finalproj.orbitflow.attendance.rule.service.AttendanceRuleService;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.event.CompanyCreatedEvent;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("회사 기본 근태 규칙 초기화 이벤트 리스너 단위 테스트")
class AttendanceRuleEventListenerTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AttendanceRuleService attendanceRuleService;

    @Mock
    private Company company;

    private AttendanceRuleEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new AttendanceRuleEventListener(
                companyRepository,
                attendanceRuleService
        );
    }

    @Test
    @DisplayName("회사를 찾으면 기본 근태 규칙 초기화 서비스를 호출한다")
    void initializesDefaultRuleWhenCompanyExists() {
        Long companyId = 1L;
        CompanyCreatedEvent event = mock(CompanyCreatedEvent.class);
        when(event.getCompanyId()).thenReturn(companyId);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        listener.handleCompanyCreated(event);

        verify(companyRepository).findById(companyId);
        verify(attendanceRuleService).createDefaultAttendanceRule(company);
    }

    @Test
    @DisplayName("회사가 없으면 초기화 서비스를 호출하지 않는다")
    void skipsInitializationWhenCompanyNotFound() {
        Long companyId = 2L;
        CompanyCreatedEvent event = mock(CompanyCreatedEvent.class);
        when(event.getCompanyId()).thenReturn(companyId);
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        listener.handleCompanyCreated(event);

        verify(companyRepository).findById(companyId);
        verifyNoInteractions(attendanceRuleService);
    }

    @Test
    @DisplayName("초기화 서비스가 실패해도 예외를 로깅하고 종료한다")
    void handlesInitializationFailure() {
        Long companyId = 3L;
        CompanyCreatedEvent event = mock(CompanyCreatedEvent.class);
        when(event.getCompanyId()).thenReturn(companyId);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        doThrow(new IllegalStateException("DB 오류"))
                .when(attendanceRuleService)
                .createDefaultAttendanceRule(company);

        assertThatCode(() -> listener.handleCompanyCreated(event))
                .doesNotThrowAnyException();

        verify(attendanceRuleService).createDefaultAttendanceRule(company);
    }
}
