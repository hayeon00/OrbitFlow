
package com.finalproj.orbitflow.approval.attendance.event.repository;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ContextConfiguration(
        classes = AttendanceEventRepositoryIntegrationTest.TestJpaApplication.class
)
class AttendanceEventRepositoryIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.finalproj.orbitflow")
    @EnableJpaRepositories(
            basePackageClasses = AttendanceEventRepository.class
    )
    static class TestJpaApplication {
    }

    @Container
    static final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add(
                "spring.datasource.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver"
        );
    }

    @Autowired
    private AttendanceEventRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long COMPANY_ID = 91001L;
    private static final long OTHER_COMPANY_ID = 91002L;

    private static final long EMPLOYEE_ID = 92001L;
    private static final long OTHER_EMPLOYEE_ID = 92002L;

    private static final long ORGANIZATION_ID = 93001L;
    private static final long CATEGORY_ID = 94001L;

    private static final long TEMPLATE_CATEGORY_ID = 95001L;
    private static final long TEMPLATE_GROUP_ID = 96001L;

    private static final long APPROVED_DOC_ID = 97001L;
    private static final long PROGRESS_DOC_ID = 97002L;
    private static final long REJECTED_DOC_ID = 97003L;

    private static final LocalDate START =
            LocalDate.of(2026, 11, 2);

    private static final LocalDate END =
            LocalDate.of(2026, 11, 4);

    @BeforeEach
    void setUp() {
        // @DataJpaTest의 트랜잭션이 테스트 종료 시
        // 자동 롤백되므로 테스트 간 데이터가 격리된다.

        jdbc.query("SHOW COLUMNS FROM template_category", rs -> {
            System.out.println(
                    "COLUMN=" + rs.getString("Field")
                            + ", TYPE=" + rs.getString("Type")
            );
        });

        jdbc.update("""
                INSERT INTO company (
                    id, name, business_number, address,
                    representative_name, representative_contact
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                COMPANY_ID, "근태테스트회사",
                "991-00-00001", "서울",
                "테스트대표", "010-0000-0001");

        jdbc.update("""
                INSERT INTO company (
                    id, name, business_number, address,
                    representative_name, representative_contact
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                OTHER_COMPANY_ID, "다른테스트회사",
                "991-00-00002", "부산",
                "다른대표", "010-0000-0002");

        jdbc.update("""
                INSERT INTO org_category (
                    id, company_id, name, is_active, is_root
                )
                VALUES (?, ?, ?, TRUE, TRUE)
                """,
                CATEGORY_ID, COMPANY_ID, "테스트조직구분");

        jdbc.update("""
                INSERT INTO organization (
                    id, company_id, category_id, name, is_active
                )
                VALUES (?, ?, ?, ?, TRUE)
                """,
                ORGANIZATION_ID, COMPANY_ID,
                CATEGORY_ID, "테스트팀");

        insertEmployee(EMPLOYEE_ID, "tester1@orbitflow.test");
        insertEmployee(OTHER_EMPLOYEE_ID, "tester2@orbitflow.test");

        jdbc.update("""
        INSERT INTO template_category (id, code, name)
        VALUES (?, ?, ?)
        """,
                TEMPLATE_CATEGORY_ID,
                "ATTENDANCE", "근태");

        jdbc.update("""
                INSERT INTO form_template_group (
                    id, company_id, name,
                    template_category_id, base_role, active
                )
                VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                TEMPLATE_GROUP_ID, COMPANY_ID,
                "출장외근테스트", TEMPLATE_CATEGORY_ID,
                "BUSINESS_TRIP");

        insertDocument(APPROVED_DOC_ID, "APPROVED");
        insertDocument(PROGRESS_DOC_ID, "IN_PROGRESS");
        insertDocument(REJECTED_DOC_ID, "REJECTED");
    }

    private void insertEmployee(long employeeId, String email) {
        jdbc.update("""
                INSERT INTO employee (
                    id, company_id, org_id, employee_no,
                    name, email, password,
                    gender, employment_type, status,
                    work_status, hire_date, role
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    'MALE', 'REGULAR', 'ACTIVE',
                    'OFF_WORK', ?, 'EMPLOYEE'
                )
                """,
                employeeId,
                COMPANY_ID,
                ORGANIZATION_ID,
                "TEST-" + employeeId,
                "테스트사원",
                email,
                "test-password",
                START);
    }

    private void insertDocument(long documentId, String status) {
        jdbc.update("""
                INSERT INTO document (
                    id, company_id, template_group_id,
                    template_version, writer_id,
                    title, status, is_deleted
                )
                VALUES (?, ?, ?, 1, ?, ?, ?, FALSE)
                """,
                documentId,
                COMPANY_ID,
                TEMPLATE_GROUP_ID,
                EMPLOYEE_ID,
                "테스트 결재 문서",
                status);
    }

    private void insertEvent(
            long employeeId,
            String role,
            long documentId,
            LocalDate start,
            LocalDate end,
            LocalDate actualEnd
    ) {
        jdbc.update("""
                INSERT INTO attendance_event (
                    company_id, employee_id, base_role,
                    start_date, end_date,
                    actual_end_date, source_document_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                COMPANY_ID,
                employeeId,
                role,
                start,
                end,
                actualEnd,
                documentId);
    }

    private boolean hasApprovedEvent(
            long companyId,
            long employeeId,
            LocalDate date
    ) {
        // 복제 SQL이 아니라 실제 Spring Data JPA 메서드 실행
        return repository.existsApprovedFullDayWorkEvent(
                companyId, employeeId, date
        );
    }

    @Test
    void 승인된_출장은_기간중에_조회된다() {
        insertEvent(
                EMPLOYEE_ID,
                "BUSINESS_TRIP",
                APPROVED_DOC_ID,
                START, END, null
        );

        assertTrue(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                LocalDate.of(2026, 11, 3)
        ));
    }

    @Test
    void 승인된_종일외근도_기간중에_조회된다() {
        insertEvent(
                EMPLOYEE_ID,
                "OUTWORK",
                APPROVED_DOC_ID,
                START, END, null
        );

        assertTrue(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                LocalDate.of(2026, 11, 3)
        ));
    }

    @Test
    void 결재진행중인_출장은_조회되지_않는다() {
        insertEvent(
                EMPLOYEE_ID,
                "BUSINESS_TRIP",
                PROGRESS_DOC_ID,
                START, END, null
        );

        assertFalse(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                LocalDate.of(2026, 11, 3)
        ));
    }

    @Test
    void 반려된_외근은_조회되지_않는다() {
        insertEvent(
                EMPLOYEE_ID,
                "OUTWORK",
                REJECTED_DOC_ID,
                START, END, null
        );

        assertFalse(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                LocalDate.of(2026, 11, 3)
        ));
    }

    @Test
    void 출장_시작일과_종료일은_포함하고_바깥날짜는_제외한다() {
        insertEvent(
                EMPLOYEE_ID,
                "BUSINESS_TRIP",
                APPROVED_DOC_ID,
                START, END, null
        );

        assertFalse(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                START.minusDays(1)
        ));

        assertTrue(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                START
        ));

        assertTrue(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                END
        ));

        assertFalse(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                END.plusDays(1)
        ));
    }

    @Test
    void 조기종료일_이후에는_출장을_조회하지_않는다() {
        LocalDate actualEnd =
                LocalDate.of(2026, 11, 2);

        insertEvent(
                EMPLOYEE_ID,
                "BUSINESS_TRIP",
                APPROVED_DOC_ID,
                START, END, actualEnd
        );

        assertTrue(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                actualEnd
        ));

        assertFalse(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                actualEnd.plusDays(1)
        ));
    }

    @Test
    void 다른직원의_출장은_현재직원에게_적용되지_않는다() {
        insertEvent(
                OTHER_EMPLOYEE_ID,
                "BUSINESS_TRIP",
                APPROVED_DOC_ID,
                START, END, null
        );

        assertFalse(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                START
        ));

        assertTrue(hasApprovedEvent(
                COMPANY_ID, OTHER_EMPLOYEE_ID,
                START
        ));
    }

    @Test
    void 다른회사ID로는_출장기록을_조회할수없다() {
        insertEvent(
                EMPLOYEE_ID,
                "BUSINESS_TRIP",
                APPROVED_DOC_ID,
                START, END, null
        );

        assertFalse(hasApprovedEvent(
                OTHER_COMPANY_ID, EMPLOYEE_ID,
                START
        ));
    }

    @Test
    void 동일날짜에_승인된_기록이_여러개여도_존재여부를_반환한다() {
        insertEvent(
                EMPLOYEE_ID,
                "BUSINESS_TRIP",
                APPROVED_DOC_ID,
                START, END, null
        );

        insertEvent(
                EMPLOYEE_ID,
                "OUTWORK",
                APPROVED_DOC_ID,
                START, END, null
        );

        assertTrue(hasApprovedEvent(
                COMPANY_ID, EMPLOYEE_ID,
                LocalDate.of(2026, 11, 3)
        ));
    }
}
