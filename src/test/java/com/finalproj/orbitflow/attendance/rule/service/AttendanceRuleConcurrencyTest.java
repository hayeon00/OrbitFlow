package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.global.config.QuerydslConfig;
import com.finalproj.orbitflow.hr.company.entity.Company;
import jakarta.persistence.EntityManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        AttendanceRuleService.class,
        QuerydslConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttendanceRuleConcurrencyTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private AttendanceRuleService attendanceRuleService;

    @Autowired
    private AttendanceRuleRepository attendanceRuleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // 기본 규칙 생성 경로에서 사용하지 않는 서비스 의존성
    @MockitoBean
    private com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository employeeRepository;

    @MockitoBean
    private com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository employeeRuleRepository;

    @MockitoBean
    private com.finalproj.orbitflow.notification.service.NotificationCommandService notificationService;

    @Test
    void simultaneousInitialization_leavesExactlyOneDefaultRule() throws Exception {
        Long companyId = createCommittedCompany();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Throwable>> futures = new ArrayList<>();

            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 시작 대기 시간 초과");
                    }

                    try {
                        // 실제 Spring 서비스 프록시를 호출하여 독립 트랜잭션 실행
                        Company company = new TransactionTemplate(transactionManager)
                                .execute(status -> entityManager.find(Company.class, companyId));

                        attendanceRuleService.createDefaultAttendanceRule(company);
                        return null; // 성공
                    } catch (Exception e) {
                        return e; // UNIQUE 충돌 가능; 결과 개수 검증과 분리
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<Throwable> future : futures) {
                // 교착/장시간 대기를 무한히 기다리지 않는다.
                Throwable failure = future.get(20, TimeUnit.SECONDS);
                if (failure != null) {
                    System.out.println("동시 초기화 요청 중 예외 발생(허용): "
                            + failure.getClass().getSimpleName()
                            + " - " + failure.getMessage());
                }
            }

            Long count = new TransactionTemplate(transactionManager)
                    .execute(status -> entityManager.createQuery(
                                    "select count(r) from AttendanceRule r " +
                                            "where r.companyId = :companyId and r.isDefault = true",
                                    Long.class)
                            .setParameter("companyId", companyId)
                            .getSingleResult());

            assertThat(count).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Long createCommittedCompany() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String unique = UUID.randomUUID().toString();
            Company company = Company.create(
                    "동시성 회사 " + unique,
                    unique.substring(0, 20),
                    "서울",
                    "대표",
                    "010-1234-5678"
            );
            entityManager.persist(company);
            entityManager.flush();
            return company.getId();
        });
    }
}
