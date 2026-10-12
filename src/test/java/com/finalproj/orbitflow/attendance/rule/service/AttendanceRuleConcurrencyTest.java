package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleCreateReqDto;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.config.QuerydslConfig;
import com.finalproj.orbitflow.global.exception.BusinessException;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.organization.entity.Organization;
import com.finalproj.orbitflow.hr.orgCategory.entity.OrgCategory;
import com.finalproj.orbitflow.notification.service.NotificationCommandService;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceRuleService.class, QuerydslConfig.class})
class AttendanceRuleConcurrencyTest {

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

    @Autowired private AttendanceRuleService service;
    @Autowired private EmployeeRuleRepository ruleRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    // 알림만 모킹; EmployeeRepository는 실제 MySQL을 사용한다.
    @MockitoBean private NotificationCommandService notificationService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameEmployeeConcurrentOverlappingCreate_onlyOneSucceeds() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 먼저 테스트 데이터 생성 트랜잭션을 커밋한다.
        long[] ids = tx.execute(status -> {
            Company company = Company.create("동시성 테스트 회사", "123-45-67890",
                    "서울", "테스트 대표", "010-1234-5678");
            entityManager.persist(company);
            entityManager.flush();

            OrgCategory category = OrgCategory.create(company.getId(), "회사", 1, true);
            entityManager.persist(category);
            entityManager.flush();

            Organization organization = Organization.createRoot(company, category.getId());
            entityManager.persist(organization);
            entityManager.flush();

            Employee employee = Employee.createAdmin(company, organization,
                    "concurrency-admin@orbitflow.test", "test-password");
            entityManager.persist(employee);
            entityManager.flush();

            return new long[]{company.getId(), employee.getId()};
        });

        assertThat(ids).isNotNull();
        long companyId = ids[0];
        long employeeId = ids[1];

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Boolean> first = register(ready, start, companyId, employeeId,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
        Callable<Boolean> second = register(ready, start, companyId, employeeId,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20));

        try {
            Future<Boolean> resultA = pool.submit(first);
            Future<Boolean> resultB = pool.submit(second);

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("두 스레드 준비 완료").isTrue();
            start.countDown();

            boolean successA = resultA.get(30, TimeUnit.SECONDS);
            boolean successB = resultB.get(30, TimeUnit.SECONDS);
            assertThat(List.of(successA, successB).stream()
                    .filter(Boolean::booleanValue).count()).isEqualTo(1);

            // 별도 트랜잭션에서 커밋된 결과 검증
            Long activeCount = tx.execute(status -> ruleRepository
                    .findByCompanyIdAndIsActiveTrueOrderByAppliedAtDesc(companyId)
                    .stream()
                    .filter(rule -> rule.getEmployeeId().equals(employeeId))
                    .count());
            assertThat(activeCount).isEqualTo(1L);
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Callable<Boolean> register(
            CountDownLatch ready, CountDownLatch start,
            long companyId, long employeeId, LocalDate from, LocalDate to
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 신호를 받지 못했습니다.");
            }

            EmpAttRuleCreateReqDto request = new EmpAttRuleCreateReqDto(
                    employeeId, LocalTime.of(9, 0), LocalTime.of(18, 0),
                    60, "동시 등록 테스트", from, to
            );
            try {
                service.createExceptionRule(companyId, request);
                return true;
            } catch (BusinessException ex) {
                // 다른 비즈니스 예외는 정당한 중복 거부로 인정하지 않는다.
                assertThat(ex.getMessage()).contains("적용 기간이 겹치는");
                return false;
            }
        };
    }
}
