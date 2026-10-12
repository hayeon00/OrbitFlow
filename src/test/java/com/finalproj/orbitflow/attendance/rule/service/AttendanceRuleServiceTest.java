package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleCreateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.config.QuerydslConfig;
import com.finalproj.orbitflow.global.exception.BusinessException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import com.finalproj.orbitflow.notification.service.NotificationCommandService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceRuleService.class, QuerydslConfig.class})
@DisplayName("예외 근태 규칙 Service-MySQL 통합 테스트")
class AttendanceRuleServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0.36")
                    .withDatabaseName("orbitflow_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final long COMPANY_ID = 1L;
    private static final long EMPLOYEE_ID = 100L;

    @Autowired AttendanceRuleService service;
    @Autowired EmployeeRuleRepository ruleRepository;
    @Autowired EntityManager entityManager;

    // 이 두 의존성만 모킹: 규칙 엔티티와 Repository는 실제 MySQL 사용
    @MockitoBean EmployeeRepository employeeRepository;
    @MockitoBean NotificationCommandService notificationService;

    @BeforeEach
    void setup() {
        given(employeeRepository.findByIdAndCompanyIdForUpdate(eq(EMPLOYEE_ID), eq(COMPANY_ID)))
                .willAnswer(invocation -> Optional.of(employee(EMPLOYEE_ID)));
        given(employeeRepository.findByIdAndCompanyIdForUpdate(eq(200L), eq(COMPANY_ID)))
                .willAnswer(invocation -> Optional.of(employee(200L)));
    }

    private Employee employee(long employeeId) {
        Employee employee = org.mockito.Mockito.mock(Employee.class);
        given(employee.getId()).willReturn(employeeId);
        given(employee.getName()).willReturn("테스트 사원");
        given(employee.getEmployeeNo()).willReturn("TEST-" + employeeId);
        return employee;
    }

    private LocalDate date(int day) {
        return LocalDate.of(2026, 10, day);
    }

    private EmpAttRuleCreateReqDto createRequest(long employeeId, int from, int to) {
        return new EmpAttRuleCreateReqDto(employeeId, LocalTime.of(9, 0),
                LocalTime.of(18, 0), 60, "테스트", date(from), date(to));
    }

    private EmpAttRuleUpdateReqDto updateRequest(int from, int to, Boolean active) {
        return new EmpAttRuleUpdateReqDto(LocalTime.of(10, 0),
                LocalTime.of(19, 0), 60, "변경", date(from), date(to), active);
    }

    private EmployeeRule existing(long employeeId, int from, int to) {
        EmployeeRule rule = EmployeeRule.create(COMPANY_ID, employeeId,
                LocalTime.of(9, 0), LocalTime.of(18, 0), 60,
                "기존 규칙", date(from), date(to));
        EmployeeRule saved = ruleRepository.saveAndFlush(rule);
        entityManager.clear();
        return saved;
    }

    @Test
    @DisplayName("겹치지 않는 규칙은 등록된다")
    void create_withoutOverlap() {
        existing(EMPLOYEE_ID, 1, 10);

        service.createExceptionRule(COMPANY_ID, createRequest(EMPLOYEE_ID, 11, 20));
        ruleRepository.flush();

        assertThat(ruleRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("기존 규칙과 날짜가 겹치면 예외를 던지고 저장하지 않는다")
    void create_withOverlap() {
        existing(EMPLOYEE_ID, 1, 10);

        assertThatThrownBy(() -> service.createExceptionRule(
                COMPANY_ID, createRequest(EMPLOYEE_ID, 10, 20)))
                .isInstanceOf(BusinessException.class);

        assertThat(ruleRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사원의 동일 기간은 허용한다")
    void create_forAnotherEmployee() {
        existing(EMPLOYEE_ID, 1, 10);

        service.createExceptionRule(COMPANY_ID, createRequest(200L, 1, 10));
        ruleRepository.flush();

        assertThat(ruleRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("수정 시 자기 자신은 중복 검사에서 제외한다")
    void update_excludesSelf() {
        EmployeeRule rule = existing(EMPLOYEE_ID, 1, 10);

        service.updateExceptionRule(COMPANY_ID, rule.getId(), updateRequest(2, 9, true));
        entityManager.flush();
        entityManager.clear();

        EmployeeRule updated = ruleRepository.findById(rule.getId()).orElseThrow();
        assertThat(updated.getValidFrom()).isEqualTo(date(2));
        assertThat(updated.getValidTo()).isEqualTo(date(9));
        assertThat(updated.getStartTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("수정 후 다른 활성 규칙과 겹치면 기존 데이터는 유지된다")
    void update_rejectsOverlapAndPreservesData() {
        EmployeeRule first = existing(EMPLOYEE_ID, 1, 10);
        existing(EMPLOYEE_ID, 20, 25);

        assertThatThrownBy(() -> service.updateExceptionRule(
                COMPANY_ID, first.getId(), updateRequest(5, 22, true)))
                .isInstanceOf(BusinessException.class);

        entityManager.clear();
        EmployeeRule unchanged = ruleRepository.findById(first.getId()).orElseThrow();
        assertThat(unchanged.getValidFrom()).isEqualTo(date(1));
        assertThat(unchanged.getValidTo()).isEqualTo(date(10));
        assertThat(unchanged.getStartTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    @DisplayName("비활성 규칙을 재활성화할 때 충돌하면 거부한다")
    void reactivate_rejectsOverlap() {
        // given
        EmployeeRule inactive = existing(EMPLOYEE_ID, 1, 10);

        EmployeeRule managed = ruleRepository
                .findById(inactive.getId())
                .orElseThrow();

        managed.delete();

        ruleRepository.flush();
        entityManager.clear();

        // 활성 규칙 추가
        existing(EMPLOYEE_ID, 5, 15);

        // when & then
        assertThatThrownBy(() ->
                service.updateExceptionRule(
                        COMPANY_ID,
                        inactive.getId(),
                        updateRequest(1, 10, true)
                )
        ).isInstanceOf(BusinessException.class);

        entityManager.clear();

        assertThat(ruleRepository.findById(inactive.getId())
                .orElseThrow()
                .getIsActive())
                .isFalse();
    }

    @Test
    @DisplayName("비활성 규칙의 기간은 새 활성 규칙 등록을 막지 않는다")
    void create_ignoresInactiveRule() {
        // given
        EmployeeRule inactive = existing(EMPLOYEE_ID, 1, 10);

        EmployeeRule managed = ruleRepository
                .findById(inactive.getId())
                .orElseThrow();

        managed.delete();

        ruleRepository.flush();
        entityManager.clear();

        // 비활성화 확인
        assertThat(ruleRepository.findById(inactive.getId())
                .orElseThrow()
                .getIsActive())
                .isFalse();

        // when
        service.createExceptionRule(
                COMPANY_ID,
                createRequest(EMPLOYEE_ID, 5, 9)
        );

        ruleRepository.flush();
        entityManager.clear();

        // then
        assertThat(ruleRepository.count()).isEqualTo(2);
    }
}
