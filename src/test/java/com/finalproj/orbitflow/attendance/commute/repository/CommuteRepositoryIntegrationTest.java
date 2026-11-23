
package com.finalproj.orbitflow.attendance.commute.repository;

import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
        classes = CommuteRepositoryIntegrationTest.TestJpaApplication.class
)
class CommuteRepositoryIntegrationTest {

    /**
     * 독립된 MySQL 테스트 컨테이너.
     */
    @Container
    static final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {

        registry.add(
                "spring.datasource.url",
                mysql::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                mysql::getUsername
        );

        registry.add(
                "spring.datasource.password",
                mysql::getPassword
        );

        registry.add(
                "spring.datasource.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver"
        );
    }

    /**
     * 테스트 전용 Spring 설정.
     *
     * 전체 OrbitFlow 프로젝트의 Repository를
     * 스캔하지 않고 CommuteRepository만 등록한다.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(
            basePackages = "com.finalproj.orbitflow"
    )
    @EnableJpaRepositories(
            basePackageClasses = CommuteRepository.class
    )
    static class TestJpaApplication {
    }

    @Autowired
    private CommuteRepository repository;

    @Autowired
    private EntityManager em;

    private static final Long COMPANY = 90001L;
    private static final Long EMPLOYEE = 90002L;

    private static final LocalDate DAY =
            LocalDate.of(2026, 10, 7);

    private static final LocalDateTime IN =
            DAY.atTime(14, 0);

    private static final LocalDateTime OUT =
            DAY.atTime(18, 0);

    /**
     * 출퇴근시간이 없는 기존 근태 행 생성.
     */
    private Attendance emptyRow(AttendanceStatus status) {

        Attendance attendance = Attendance.builder()
                .companyId(COMPANY)
                .employeeId(EMPLOYEE)
                .workDate(DAY)
                .commuteAt(null)
                .leaveAt(null)
                .status(status)
                .isCorrected(false)
                .build();

        Attendance saved =
                repository.saveAndFlush(attendance);

        em.clear();

        return saved;
    }

    /**
     * 영속성 컨텍스트를 비우고
     * 실제 DB 기록을 다시 조회한다.
     */
    private Attendance reload() {

        em.flush();
        em.clear();

        return repository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        COMPANY,
                        EMPLOYEE,
                        DAY
                )
                .orElseThrow();
    }

    /**
     * 1. 이미 생성된 VACATION 근태 행에
     * 반차 출근시간을 정상적으로 기록하는지 확인.
     */
    @Test
    void 기존_휴가_상태의_빈_행에_반차_출근시간을_기록한다() {

        emptyRow(AttendanceStatus.VACATION);

        int updated =
                repository.updateMissingCheckIn(
                        COMPANY,
                        EMPLOYEE,
                        DAY,
                        IN,
                        AttendanceStatus.ON_TIME
                );

        assertEquals(1, updated);

        Attendance record = reload();

        assertEquals(IN, record.getCommuteAt());

        assertEquals(
                AttendanceStatus.ON_TIME,
                record.getStatus()
        );

        assertNull(record.getLeaveAt());

        assertEquals(
                1L,
                repository.countByCompanyIdAndEmployeeIdAndWorkDate(
                        COMPANY,
                        EMPLOYEE,
                        DAY
                )
        );
    }

    /**
     * 2. 기존 출근시간이 존재하는 경우
     * 새 출근시간으로 덮어쓰지 않는지 확인.
     */
    @Test
    void 이미_출근한_행의_시간은_덮어쓰지_않는다() {

        emptyRow(AttendanceStatus.VACATION);

        repository.updateMissingCheckIn(
                COMPANY,
                EMPLOYEE,
                DAY,
                IN,
                AttendanceStatus.ON_TIME
        );

        int updated =
                repository.updateMissingCheckIn(
                        COMPANY,
                        EMPLOYEE,
                        DAY,
                        DAY.atTime(14, 30),
                        AttendanceStatus.LATE
                );

        assertEquals(0, updated);

        Attendance record = reload();

        assertEquals(IN, record.getCommuteAt());

        assertEquals(
                AttendanceStatus.ON_TIME,
                record.getStatus()
        );
    }

    /**
     * 3. 실제 출근시간이 없는 근태 행에는
     * 퇴근시간을 기록할 수 없어야 한다.
     */
    @Test
    void 출근시간이_없는_행에는_퇴근시간을_저장하지_않는다() {

        emptyRow(AttendanceStatus.VACATION);

        int updated =
                repository.updateLeaveAtIfNull(
                        COMPANY,
                        EMPLOYEE,
                        DAY,
                        OUT
                );

        assertEquals(0, updated);

        assertNull(reload().getLeaveAt());
    }

    /**
     * 4. 출근한 직원은 퇴근시간을
     * 한 번만 기록할 수 있어야 한다.
     */
    @Test
    void 출근한_행은_퇴근을_한번만_기록한다() {

        emptyRow(AttendanceStatus.VACATION);

        repository.updateMissingCheckIn(
                COMPANY,
                EMPLOYEE,
                DAY,
                IN,
                AttendanceStatus.ON_TIME
        );

        assertEquals(
                1,
                repository.updateLeaveAtIfNull(
                        COMPANY,
                        EMPLOYEE,
                        DAY,
                        OUT
                )
        );

        assertEquals(
                0,
                repository.updateLeaveAtIfNull(
                        COMPANY,
                        EMPLOYEE,
                        DAY,
                        DAY.atTime(18, 20)
                )
        );

        assertEquals(OUT, reload().getLeaveAt());
    }

    /**
     * 5. 동일한 회사·직원·날짜로 출근 INSERT를
     * 두 번 실행해도 행이 중복 생성되지 않아야 한다.
     */
    @Test
    void 일반_출근_INSERT는_중복_행을_만들지_않는다() {

        LocalDateTime nine =
                DAY.atTime(9, 0);

        repository.insertCheckInIgnoreDuplicate(
                COMPANY,
                EMPLOYEE,
                DAY,
                nine,
                AttendanceStatus.ON_TIME.name()
        );

        repository.insertCheckInIgnoreDuplicate(
                COMPANY,
                EMPLOYEE,
                DAY,
                DAY.atTime(9, 10),
                AttendanceStatus.LATE.name()
        );

        Attendance record = reload();

        assertEquals(
                1L,
                repository.countByCompanyIdAndEmployeeIdAndWorkDate(
                        COMPANY,
                        EMPLOYEE,
                        DAY
                )
        );

        assertEquals(nine, record.getCommuteAt());

        assertEquals(
                AttendanceStatus.ON_TIME,
                record.getStatus()
        );
    }
}
