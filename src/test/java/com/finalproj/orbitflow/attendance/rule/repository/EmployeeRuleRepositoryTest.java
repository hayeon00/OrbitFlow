package com.finalproj.orbitflow.attendance.rule.repository;

import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;
import com.finalproj.orbitflow.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@DisplayName("EmployeeRuleRepository - MySQL 기간 중복 조회 통합 테스트")
class EmployeeRuleRepositoryTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.36")
                    .withDatabaseName("orbitflow_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private EmployeeRuleRepository repository;

    @Autowired
    private EntityManager entityManager;

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 100L;

    private static LocalDate date(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    private EmployeeRule saveRule(Long companyId, Long employeeId,
                                  LocalDate from, LocalDate to) {
        EmployeeRule rule = EmployeeRule.create(
                companyId, employeeId,
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                60, "테스트 규칙", from, to
        );
        EmployeeRule saved = repository.saveAndFlush(rule);
        entityManager.clear();
        return saved;
    }

    private List<EmployeeRule> overlaps(Long companyId, Long employeeId,
                                        LocalDate from, LocalDate to,
                                        Long excludeRuleId) {
        return repository.findOverlappingRulesForUpdate(
                companyId, employeeId, from, to, excludeRuleId
        );
    }

    @Test
    @DisplayName("1. 날짜가 일부 겹치면 중복으로 조회한다")
    void overlappingPeriods() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 15));

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 10), date(10, 20), null)).hasSize(1);
    }

    @Test
    @DisplayName("2. 종료일과 시작일이 같은 날짜면 중복이다")
    void touchingBoundaryOverlaps() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 10));

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 10), date(10, 20), null)).hasSize(1);
    }

    @Test
    @DisplayName("3. 기존 규칙 종료일 다음 날 시작하면 중복이 아니다")
    void nextDayDoesNotOverlap() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 10));

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 11), date(10, 20), null)).isEmpty();
    }

    @Test
    @DisplayName("4. 새 규칙이 기존 규칙 안에 완전히 포함되면 중복이다")
    void containedPeriodOverlaps() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 31));

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 7), date(10, 9), null)).hasSize(1);
    }

    @Test
    @DisplayName("5. 기존 규칙의 종료일이 null이면 이후 날짜와 겹친다")
    void existingOpenEndedPeriodOverlaps() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), null);

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(11, 1), date(11, 30), null)).hasSize(1);
    }

    @Test
    @DisplayName("6. 신규 규칙의 종료일이 null이면 이후 기존 규칙과 겹친다")
    void newOpenEndedPeriodOverlaps() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(12, 1), date(12, 31));

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 1), null, null)).hasSize(1);
    }

    @Test
    @DisplayName("7. 다른 사원의 규칙은 중복 대상이 아니다")
    void differentEmployeeDoesNotOverlap() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 31));

        assertThat(overlaps(COMPANY_ID, 200L,
                date(10, 1), date(10, 31), null)).isEmpty();
    }

    @Test
    @DisplayName("8. 같은 사원 ID라도 다른 회사 규칙은 조회하지 않는다")
    void differentCompanyDoesNotOverlap() {
        saveRule(COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 31));

        assertThat(overlaps(2L, EMPLOYEE_ID,
                date(10, 1), date(10, 31), null)).isEmpty();
    }

    @Test
    @DisplayName("9. 비활성화된 규칙은 중복 대상이 아니다")
    void inactiveRuleDoesNotOverlap() {
        EmployeeRule saved = saveRule(
                COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 31));

        EmployeeRule managed = repository.findById(saved.getId()).orElseThrow();
        managed.delete();
        repository.flush();
        entityManager.clear();

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 10), date(10, 20), null)).isEmpty();
    }

    @Test
    @DisplayName("10. 수정 시 자기 자신의 ID는 중복 검사에서 제외한다")
    void excludesCurrentRule() {
        EmployeeRule saved = saveRule(
                COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 31));

        assertThat(overlaps(COMPANY_ID, EMPLOYEE_ID,
                date(10, 5), date(10, 20), saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("11. 자기 자신을 제외해도 다른 활성 규칙과 겹치면 조회한다")
    void detectsAnotherRuleWhenExcludingCurrent() {
        EmployeeRule first = saveRule(
                COMPANY_ID, EMPLOYEE_ID, date(10, 1), date(10, 10));
        EmployeeRule second = saveRule(
                COMPANY_ID, EMPLOYEE_ID, date(10, 20), date(10, 31));

        List<EmployeeRule> result = overlaps(
                COMPANY_ID, EMPLOYEE_ID,
                date(10, 5), date(10, 25), first.getId()
        );

        assertThat(result)
                .extracting(EmployeeRule::getId)
                .containsExactly(second.getId());
    }
}
