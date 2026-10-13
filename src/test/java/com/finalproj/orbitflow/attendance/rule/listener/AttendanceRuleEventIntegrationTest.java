package com.finalproj.orbitflow.attendance.rule.listener;

import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.global.file.storage.FileStorage;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.event.CompanyCreatedEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.task.execution.pool.core-size=2",
        "spring.task.execution.pool.max-size=4"
})
@Testcontainers
class AttendanceRuleEventIntegrationTest {

    @MockitoBean
    private FileStorage fileStorage;

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
        // 환경별 외부 연동 설정이 필요하면 기존 Testcontainers 통합 테스트의
        // 테스트 프로필 / @MockBean 대체 방식에 맞춰 추가하세요.
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AttendanceRuleRepository attendanceRuleRepository;

    private final Duration timeout = Duration.ofSeconds(10);

    @Test
    void afterCommit_createsDefaultRule() {
        Long companyId = createCompanyAndPublish(true);

        awaitUntil(() -> defaultRuleExists(companyId), timeout);

        assertThat(defaultRuleExists(companyId)).isTrue();
    }

    @Test
    void rollback_doesNotCreateDefaultRule() {
        Long companyId = createCompanyAndPublish(false);

        // 롤백 후 이벤트가 잘못 발행되는지 확인하기 위한 짧은 안정성 대기.
        // 이벤트 미실행을 완전히 증명하는 방법은 리스너 호출 관찰이지만,
        // 여기서는 최종 DB 상태를 검증합니다.
        awaitFor(Duration.ofMillis(500));

        assertThat(defaultRuleExists(companyId)).isFalse();
    }

    @Test
    void republishedEvent_doesNotCreateDuplicate() {
        Long companyId = createCompanyAndPublish(true);

        awaitUntil(() -> defaultRuleExists(companyId), timeout);

        // 이미 생성된 이후에 같은 이벤트를 다시 발행해 순차 재실행의 멱등성을 검증한다.
        // 동시 발행 경쟁 조건까지 검증하는 테스트는 아니다.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CompanyCreatedEvent(companyId))
        );

        // 기존 서비스는 중복 이벤트에 대해 '조용히 반환'하므로 단순 건수 조회만으로
        // 두 번째 처리 완료 시점을 알 수 없다. 최소한 첫 번째 처리 완료 상태와
        // 이후에도 기본 규칙 조회가 정상인지를 검증한다.
        awaitFor(Duration.ofMillis(500));

        assertThat(defaultRuleExists(companyId)).isTrue();
        // 실제 중복 INSERT 여부까지 검증하려면 repository.countByCompanyId 메서드를
        // 추가하거나 EntityManager의 count 쿼리를 사용해야 한다.
        assertThat(countRules(companyId)).isEqualTo(1L);
    }

    private Long createCompanyAndPublish(boolean commit) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 롤백 테스트에서도 생성된 회사 ID를 조회하기 위해 Atomic 참조 사용.
        java.util.concurrent.atomic.AtomicReference<Long> companyId = new java.util.concurrent.atomic.AtomicReference<>();

        tx.executeWithoutResult(status -> {
            String unique = UUID.randomUUID().toString();

            Company company = Company.create(
                    "근태이벤트테스트-" + unique,
                    unique.substring(0, 10),
                    "서울",
                    "테스트 대표",
                    "010-1234-5678"
            );
            entityManager.persist(company);
            entityManager.flush();

            companyId.set(company.getId());
            eventPublisher.publishEvent(new CompanyCreatedEvent(company.getId()));

            if (!commit) {
                status.setRollbackOnly();
            }
        });

        return companyId.get();
    }

    private boolean defaultRuleExists(Long companyId) {
        return attendanceRuleRepository.findByCompanyIdAndIsDefaultTrue(companyId).isPresent();
    }

    private long countRules(Long companyId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> entityManager.createQuery(
                        "select count(r) from AttendanceRule r where r.companyId = :companyId", Long.class)
                .setParameter("companyId", companyId)
                .getSingleResult());
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            awaitFor(Duration.ofMillis(100));
        }
        throw new AssertionError("기본 근태 규칙 생성 대기 시간 초과: " + timeout);
    }

    private static void awaitFor(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("테스트 대기 중 인터럽트", e);
        }
    }
}
