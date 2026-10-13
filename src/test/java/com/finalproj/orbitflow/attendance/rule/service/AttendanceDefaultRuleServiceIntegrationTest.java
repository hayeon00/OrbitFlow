package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.dto.request.DefaultRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.global.config.QuerydslConfig;
import com.finalproj.orbitflow.hr.company.entity.Company;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceRuleService.class, QuerydslConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("회사 기본 근태 규칙 Service-MySQL 통합 테스트")
class AttendanceDefaultRuleServiceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
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

    @Autowired
    AttendanceRuleService service;

    @Autowired
    AttendanceRuleRepository repository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    // 이 테스트는 기본 규칙 수정만 다루므로 관련 없는 알림 의존성만 모킹한다.
    @MockitoBean
    com.finalproj.orbitflow.notification.service.NotificationCommandService notificationService;

    private Long companyId;

    @BeforeEach
    void setUp() {
        // 먼저 실제 회사를 저장하고 커밋한다.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        companyId = tx.execute(status -> {
            Company company = Company.create(
                    "기본규칙 통합테스트 " + java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID().toString().substring(0, 20),
                    "서울",
                    "테스트 대표",
                    "010-1234-5678"
            );
            entityManager.persist(company);
            entityManager.flush();

            AttendanceRule rule = AttendanceRule.builder()
                    .companyId(company.getId())
                    .name("표준 근무 규칙")
                    .defaultStartTime(LocalTime.of(9, 0))
                    .defaultEndTime(LocalTime.of(18, 0))
                    .defaultBreakMinutes(60)
                    .isDefault(true)
                    .build();

            repository.saveAndFlush(rule);
            return company.getId();
        });
    }

    private void update(LocalTime start, LocalTime end, Integer breakMinutes) {
        service.updateDefaultRule(
                companyId,
                new DefaultRuleUpdateReqDto(start, end, breakMinutes)
        );
    }

    private AttendanceRule readCommitted() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            AttendanceRule rule = repository
                    .findByCompanyIdAndIsDefaultTrue(companyId)
                    .orElseThrow();
            // 검증은 트랜잭션 밖에서 수행하므로 영속성 컨텍스트와 분리해도 기본 필드는 조회 가능.
            return rule;
        });
    }

    private void assertUnchanged() {
        AttendanceRule persisted = readCommitted();
        assertThat(persisted.getDefaultStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(persisted.getDefaultEndTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(persisted.getDefaultBreakMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("정상 수정은 트랜잭션 커밋 후 DB에 반영된다")
    void updateValidRule_persistsInDatabase() {
        update(LocalTime.of(8, 0), LocalTime.of(17, 0), 30);

        AttendanceRule persisted = readCommitted();
        assertThat(persisted.getDefaultStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(persisted.getDefaultEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(persisted.getDefaultBreakMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("기존 퇴근 시간보다 늦은 출근 시간 수정은 거부되고 DB 값은 유지된다")
    void invalidStartTime_preservesDatabase() {
        assertThatThrownBy(() -> update(LocalTime.of(19, 0), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertUnchanged();
    }

    @Test
    @DisplayName("기존 출근 시간보다 빠른 퇴근 시간 수정은 거부되고 DB 값은 유지된다")
    void invalidEndTime_preservesDatabase() {
        assertThatThrownBy(() -> update(null, LocalTime.of(8, 0), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertUnchanged();
    }

    @Test
    @DisplayName("휴게 시간이 허용 범위를 벗어나면 수정을 거부하고 DB 값은 유지된다")
    void invalidBreakMinutes_preservesDatabase() {
        assertThatThrownBy(() -> update(LocalTime.of(10, 0), LocalTime.of(19, 0), 500))
                .isInstanceOf(IllegalArgumentException.class);

        assertUnchanged();
    }

    @Test
    @DisplayName("부분 수정 시 변경하지 않은 필드는 기존 값이 유지된다")
    void partialUpdate_preservesOtherFields() {
        update(LocalTime.of(10, 0), null, null);

        AttendanceRule persisted = readCommitted();
        assertThat(persisted.getDefaultStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(persisted.getDefaultEndTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(persisted.getDefaultBreakMinutes()).isEqualTo(60);
    }
}
