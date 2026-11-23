
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveBalance;
import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        classes = LeaveGrantIntegrationTest.TestJpaApplication.class
)
@Import({
        LeaveService.class,
        AnnualLeaveAccrualPolicy.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LeaveGrantIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.finalproj.orbitflow")
    @EnableJpaRepositories(
            basePackageClasses = LeaveGrantRepository.class
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


    private static final Long COMPANY_ID = 91001L;
    private static final Long EMPLOYEE_ID = 92001L;

    private static final LocalDate HIRE_DATE =
            LocalDate.of(2026, 9, 1);

    private static final LocalDate FIRST_GRANT_DATE =
            LocalDate.of(2026, 10, 1);

    private static final LocalDate SECOND_GRANT_DATE =
            LocalDate.of(2026, 11, 1);

    @Autowired
    private LeaveService leaveService;

    // 실제 MySQL에 연결되는 Repository
    @Autowired
    private LeaveGrantRepository leaveGrantRepository;

    @Autowired
    private LeaveBalanceRepository leaveBalanceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // 이번 테스트의 대상이 아닌 외부 의존성만 Mock
    @MockitoBean
    private EmployeeRepository employeeRepository;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private AttendanceRecordRepository attendanceRecordRepository;

    @MockitoBean
    private CommuteRepository commuteRepository;

    @MockitoBean
    private MonthlyAttendanceService monthlyAttendanceService;

    @MockitoBean
    private LeaveUsageAllocationService leaveUsageAllocationService;

    @MockitoBean
    private AttendanceEventRepository attendanceEventRepository;

    @MockitoBean
    private AnnualAttendanceService annualAttendanceService;

    @BeforeEach
    void setUp() {
        // @Transactional(NOT_SUPPORTED)이므로
        // 이전 테스트의 실제 저장 데이터를 정리한다.
        jdbc.update(
                "DELETE FROM grant_history WHERE employee_id = ?",
                EMPLOYEE_ID
        );

        jdbc.update(
                "DELETE FROM leave_balance WHERE employee_id = ?",
                EMPLOYEE_ID
        );

        Employee employee = mock(Employee.class);
        Company company = mock(Company.class);

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(employee));

        when(employee.getId()).thenReturn(EMPLOYEE_ID);
        when(employee.getStatus()).thenReturn(EmployeeStatus.ACTIVE);
        when(employee.getHireDate()).thenReturn(HIRE_DATE);
        when(employee.getCompany()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);

        // 개근 평가 자체는 별도 서비스에서 테스트한 영역.
        // 여기서는 연차 부여 결과와 DB 트랜잭션을 검증한다.
        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(EMPLOYEE_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(MonthlyAttendanceResult.PERFECT);
    }

    private void grant(LocalDate grantDate) {
        leaveService.grantMonthlyLeave(
                EMPLOYEE_ID,
                grantDate
        );
    }

    private long grantCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM grant_history
                WHERE company_id = ?
                  AND employee_id = ?
                  AND grant_type = 'ANNUAL_MONTHLY'
                """,
                Long.class,
                COMPANY_ID,
                EMPLOYEE_ID
        );
    }

    private BigDecimal totalGranted() {
        return jdbc.queryForObject(
                """
                SELECT total_granted
                FROM leave_balance
                WHERE company_id = ?
                  AND employee_id = ?
                  AND year = 2026
                """,
                BigDecimal.class,
                COMPANY_ID,
                EMPLOYEE_ID
        );
    }

    private BigDecimal remainingDays() {
        return jdbc.queryForObject(
                """
                SELECT remaining_days
                FROM leave_balance
                WHERE company_id = ?
                  AND employee_id = ?
                  AND year = 2026
                """,
                BigDecimal.class,
                COMPANY_ID,
                EMPLOYEE_ID
        );
    }


    private static final LocalDate REGULAR_GRANT_DATE =
            LocalDate.of(2025, 3, 10);

    private static final int REGULAR_YEAR = 2025;

    private void givenRegularEmployee(LocalDate hireDate) {

        // 기존 @BeforeEach에서 생성한 Employee Mock 재사용
        Employee employee = employeeRepository
                .findById(EMPLOYEE_ID)
                .orElseThrow();

        when(employee.getHireDate())
                .thenReturn(hireDate);
    }

    private void givenAnnualSummary(
            LocalDate periodStart,
            int scheduled,
            int attended,
            int undetermined
    ) {
        when(annualAttendanceService.evaluate(
                COMPANY_ID,
                EMPLOYEE_ID,
                periodStart,
                REGULAR_GRANT_DATE.minusDays(1)
        )).thenReturn(
                new AnnualAttendanceService.AnnualAttendanceSummary(
                        scheduled,
                        attended,
                        undetermined
                )
        );
    }

    private void grantRegular() {
        leaveService.grantRegularAnnualLeave(
                EMPLOYEE_ID,
                REGULAR_GRANT_DATE
        );
    }

    private long regularGrantCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM grant_history
                 WHERE company_id = ?
                   AND employee_id = ?
                   AND grant_type = 'ANNUAL_REGULAR'
                   AND grant_date = ?
                """,
                Long.class,
                COMPANY_ID,
                EMPLOYEE_ID,
                REGULAR_GRANT_DATE
        );
    }

    private BigDecimal regularGrantedDays() {
        return jdbc.queryForObject(
                """
                SELECT granted_days
                  FROM grant_history
                 WHERE company_id = ?
                   AND employee_id = ?
                   AND grant_type = 'ANNUAL_REGULAR'
                   AND grant_date = ?
                """,
                BigDecimal.class,
                COMPANY_ID,
                EMPLOYEE_ID,
                REGULAR_GRANT_DATE
        );
    }

    private BigDecimal regularTotalGranted() {
        return jdbc.queryForObject(
                """
                SELECT total_granted
                  FROM leave_balance
                 WHERE company_id = ?
                   AND employee_id = ?
                   AND year = ?
                """,
                BigDecimal.class,
                COMPANY_ID,
                EMPLOYEE_ID,
                REGULAR_YEAR
        );
    }

    private BigDecimal regularRemainingDays() {
        return jdbc.queryForObject(
                """
                SELECT remaining_days
                  FROM leave_balance
                 WHERE company_id = ?
                   AND employee_id = ?
                   AND year = ?
                """,
                BigDecimal.class,
                COMPANY_ID,
                EMPLOYEE_ID,
                REGULAR_YEAR
        );
    }

    private void assertDays(
            String expected,
            BigDecimal actual
    ) {
        assertNotNull(actual);
        assertEquals(
                0,
                new BigDecimal(expected).compareTo(actual)
        );
    }


    @Test
    void 최초_월별연차_부여시_이력과_잔여량이_함께_저장된다() {
        grant(FIRST_GRANT_DATE);

        assertEquals(1L, grantCount());

        assertEquals(
                0,
                BigDecimal.ONE.compareTo(totalGranted())
        );

        assertEquals(
                0,
                BigDecimal.ONE.compareTo(remainingDays())
        );
    }

    @Test
    void 같은_발생일에_다시_실행해도_중복_부여되지_않는다() {
        grant(FIRST_GRANT_DATE);
        grant(FIRST_GRANT_DATE);

        assertEquals(1L, grantCount());

        assertEquals(
                0,
                BigDecimal.ONE.compareTo(totalGranted())
        );

        assertEquals(
                0,
                BigDecimal.ONE.compareTo(remainingDays())
        );
    }

    @Test
    void 서로_다른_발생일은_각각_부여된다() {
        grant(FIRST_GRANT_DATE);
        grant(SECOND_GRANT_DATE);

        assertEquals(2L, grantCount());

        assertEquals(
                0,
                new BigDecimal("2.00").compareTo(totalGranted())
        );

        assertEquals(
                0,
                new BigDecimal("2.00").compareTo(remainingDays())
        );
    }



    @Test
    void 잔여량_저장에_실패하면_발생이력도_롤백된다() {

        // 1. 기존 연차 잔여량 1일 저장
        LeaveBalance balance = LeaveBalance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .year(2026)
                .totalGranted(new BigDecimal("1.00"))
                .remainingDays(new BigDecimal("1.00"))
                .build();

        leaveBalanceRepository.saveAndFlush(balance);

        // 실제 저장이 완료됐는지 확인
        assertEquals(
                0,
                new BigDecimal("1.00").compareTo(totalGranted())
        );

        // 2. 테스트 컨테이너에만 CHECK 제약조건 추가
        // 1일은 허용하지만 2일로 증가하면 DB가 UPDATE를 거부한다.
        jdbc.execute("""
            ALTER TABLE leave_balance
            ADD CONSTRAINT chk_test_leave_balance_limit
            CHECK (total_granted <= 1.00)
            """);

        try {
            // 3. 실제 연차 부여 로직 호출
            // grant_history INSERT 후 leave_balance를
            // 1일 -> 2일로 갱신하려다 CHECK 위반이 발생해야 한다.
            assertThrows(
                    RuntimeException.class,
                    () -> grant(FIRST_GRANT_DATE)
            );

            // 4. 오류 이후 실제 DB 데이터 확인
            // 트랜잭션이 롤백되었다면 INSERT된 발생 이력도 없어야 한다.
            assertEquals(
                    0L,
                    grantCount(),
                    "잔여량 갱신 실패 시 발생 이력도 롤백되어야 합니다."
            );

            assertEquals(
                    0,
                    new BigDecimal("1.00").compareTo(totalGranted()),
                    "기존 누적 부여량이 변경되면 안 됩니다."
            );

            assertEquals(
                    0,
                    new BigDecimal("1.00").compareTo(remainingDays()),
                    "기존 잔여량이 변경되면 안 됩니다."
            );

        } finally {
            // 5. 테스트 전용 제약조건 제거
            jdbc.execute("""
                ALTER TABLE leave_balance
                DROP CHECK chk_test_leave_balance_limit
                """);
        }
    }



    @Test
    @Timeout(60)
    void 서로_다른_발생일을_동시에_부여해도_잔여량이_정확해야한다()
            throws Exception {

        // 1. 실제 MySQL에 최초 연차 잔여량 0일을 생성한다.
        //
        // 두 트랜잭션이 동일한 기존 잔여량 행을
        // 갱신하는 상황을 검증하기 위한 준비다.
        LeaveBalance initialBalance = LeaveBalance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .year(2026)
                .totalGranted(BigDecimal.ZERO)
                .remainingDays(BigDecimal.ZERO)
                .build();

        leaveBalanceRepository.saveAndFlush(initialBalance);

        assertEquals(0, BigDecimal.ZERO.compareTo(totalGranted()));
        assertEquals(0, BigDecimal.ZERO.compareTo(remainingDays()));

        // 2. 두 요청이 개근 평가를 마친 뒤
        // 가능한 한 동시에 연차 저장 단계로 진입하도록 한다.
        //
        // 실제 Repository를 Mock으로 바꾸지 않는다.
        CyclicBarrier evaluationBarrier = new CyclicBarrier(2);

        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(EMPLOYEE_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenAnswer(invocation -> {

            evaluationBarrier.await(15, TimeUnit.SECONDS);

            return MonthlyAttendanceResult.PERFECT;
        });

        // 3. 작업 스레드 2개를 준비한다.
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch startGate = new CountDownLatch(1);

        try {
            // 첫 번째 요청: 입사 1개월 차 연차 발생
            Future<Void> first = executor.submit(() -> {

                startGate.await();

                leaveService.grantMonthlyLeave(
                        EMPLOYEE_ID,
                        FIRST_GRANT_DATE
                );

                return null;
            });

            // 두 번째 요청: 입사 2개월 차 연차 발생
            Future<Void> second = executor.submit(() -> {

                startGate.await();

                leaveService.grantMonthlyLeave(
                        EMPLOYEE_ID,
                        SECOND_GRANT_DATE
                );

                return null;
            });

            // 4. 두 요청의 시작 신호를 동시에 보낸다.
            startGate.countDown();

            // 서비스 실행 도중 예외가 발생하면
            // Future.get()에서 테스트가 실패한다.
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

        } finally {
            executor.shutdownNow();
        }

        // 5. 연차 발생 이력은 각각 한 건씩 존재해야 한다.
        assertEquals(
                2L,
                grantCount(),
                "서로 다른 발생일의 연차 이력은 2건이어야 합니다."
        );

        // 6. 누적 부여량은 정확히 2일이어야 한다.
        assertEquals(
                0,
                new BigDecimal("2.00").compareTo(totalGranted()),
                "발생 이력 2건에 대해 누적 부여량도 2일이어야 합니다."
        );

        // 7. 잔여량도 정확히 2일이어야 한다.
        assertEquals(
                0,
                new BigDecimal("2.00").compareTo(remainingDays()),
                "연차 차감이 없으므로 잔여량도 2일이어야 합니다."
        );
    }


    @Test
    void 입사1주년_정기연차15일이_DB에_저장된다() {

        LocalDate hireDate = LocalDate.of(2024, 3, 10);

        givenRegularEmployee(hireDate);
        givenAnnualSummary(
                hireDate,
                200,
                160,
                0
        );

        grantRegular();

        // 실제 발생 이력 1건
        assertEquals(1L, regularGrantCount());

        // 발생 이력의 부여 일수 15일
        assertDays("15.00", regularGrantedDays());

        // 연도별 누적 부여량과 잔여량도 각각 15일
        assertDays("15.00", regularTotalGranted());
        assertDays("15.00", regularRemainingDays());
    }

    @Test
    void 입사3주년_정기연차16일이_DB에_저장된다() {

        LocalDate hireDate = LocalDate.of(2022, 3, 10);

        givenRegularEmployee(hireDate);
        givenAnnualSummary(
                LocalDate.of(2024, 3, 10),
                200,
                190,
                0
        );

        grantRegular();

        assertEquals(1L, regularGrantCount());
        assertDays("16.00", regularGrantedDays());
        assertDays("16.00", regularTotalGranted());
        assertDays("16.00", regularRemainingDays());
    }

    @Test
    void 같은_정기연차를_두번_실행해도_중복지급하지_않는다() {

        LocalDate hireDate = LocalDate.of(2024, 3, 10);

        givenRegularEmployee(hireDate);
        givenAnnualSummary(
                hireDate,
                200,
                200,
                0
        );

        grantRegular();
        grantRegular();

        // 같은 직원·유형·발생일이면 이력은 1건
        assertEquals(1L, regularGrantCount());

        // 잔여량도 30일이 아닌 15일
        assertDays("15.00", regularTotalGranted());
        assertDays("15.00", regularRemainingDays());
    }

    @Test
    void 출근율80퍼센트미만이면_DB에_정기연차가_생기지_않는다() {

        LocalDate hireDate = LocalDate.of(2024, 3, 10);

        givenRegularEmployee(hireDate);
        givenAnnualSummary(
                hireDate,
                200,
                159,
                0
        );

        grantRegular();

        // 미부여 시 발생 이력 없음
        assertEquals(0L, regularGrantCount());

        // 잔여량 행도 새로 만들어지지 않아야 함
        Long balanceCount = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM leave_balance
                 WHERE company_id = ?
                   AND employee_id = ?
                   AND year = ?
                """,
                Long.class,
                COMPANY_ID,
                EMPLOYEE_ID,
                REGULAR_YEAR
        );

        assertEquals(0L, balanceCount);
    }

    @Test
    void 정기연차_잔여량_갱신에_실패하면_발생이력도_롤백된다() {

        LocalDate hireDate = LocalDate.of(2024, 3, 10);

        givenRegularEmployee(hireDate);
        givenAnnualSummary(
                hireDate,
                200,
                200,
                0
        );

        // 테스트 전용 기존 잔여량
        LeaveBalance initialBalance = LeaveBalance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .year(REGULAR_YEAR)
                .totalGranted(new BigDecimal("1.00"))
                .remainingDays(new BigDecimal("1.00"))
                .build();

        leaveBalanceRepository.saveAndFlush(initialBalance);

        // 실제 MySQL에서 잔여량 증가를 실패시키는
        // 테스트 전용 CHECK 제약조건
        jdbc.execute(
                """
                ALTER TABLE leave_balance
                ADD CONSTRAINT chk_regular_grant_rollback
                CHECK (total_granted <= 1.00)
                """
        );

        try {

            // 15일 증가 시 DB 제약조건 위반
            assertThrows(
                    RuntimeException.class,
                    this::grantRegular
            );

            // 앞서 INSERT된 발생 이력도 롤백되어야 함
            assertEquals(0L, regularGrantCount());

            // 기존 잔여량 값 유지
            assertDays("1.00", regularTotalGranted());
            assertDays("1.00", regularRemainingDays());

        } finally {

            jdbc.execute(
                    """
                    ALTER TABLE leave_balance
                    DROP CHECK chk_regular_grant_rollback
                    """
            );
        }
    }


    @Test
    void 한_직원의_부여가_실패해도_다른_직원은_정상지급되고_재실행으로_복구된다() {

        final Long secondEmployeeId = 92002L;
        final LocalDate today = LocalDate.of(2026, 11, 5);
        final int YEAR = 2026;

        // 이전 테스트 실행에서 남은 데이터가 없도록 정리
        jdbc.update(
                "DELETE FROM grant_history WHERE employee_id = ?",
                secondEmployeeId
        );
        jdbc.update(
                "DELETE FROM leave_balance WHERE employee_id = ?",
                secondEmployeeId
        );

        // 기존 @BeforeEach에서 설정한 첫 번째 직원
        Employee firstEmployee = employeeRepository
                .findById(EMPLOYEE_ID)
                .orElseThrow();

        Company company = firstEmployee.getCompany();

        // 두 번째 직원: 2026-10-01 입사, 11-01에 첫 월별 연차 발생
        Employee secondEmployee = mock(Employee.class);

        when(secondEmployee.getId())
                .thenReturn(secondEmployeeId);

        when(secondEmployee.getStatus())
                .thenReturn(EmployeeStatus.ACTIVE);

        when(secondEmployee.getHireDate())
                .thenReturn(LocalDate.of(2026, 10, 1));

        when(secondEmployee.getCompany())
                .thenReturn(company);

        when(employeeRepository.findById(secondEmployeeId))
                .thenReturn(Optional.of(secondEmployee));

        when(companyRepository.findAll())
                .thenReturn(List.of(company));

        when(employeeRepository.findByCompanyIdAndStatus(
                COMPANY_ID,
                EmployeeStatus.ACTIVE
        )).thenReturn(List.of(
                firstEmployee,
                secondEmployee
        ));

        when(monthlyAttendanceService.evaluate(
                eq(COMPANY_ID),
                eq(secondEmployeeId),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(MonthlyAttendanceResult.PERFECT);

        // A 직원에게 이미 1일이 있다고 가정
        LeaveBalance firstBalance = LeaveBalance.builder()
                .companyId(COMPANY_ID)
                .employeeId(EMPLOYEE_ID)
                .year(YEAR)
                .totalGranted(new BigDecimal("1.00"))
                .remainingDays(new BigDecimal("1.00"))
                .build();

        leaveBalanceRepository.saveAndFlush(firstBalance);

        boolean checkAdded = false;

        try {
            // total_granted가 1일을 초과하면 저장 실패
            // A: 기존 1일 + 월별 부여 1일 -> 실패
            // B: 최초 1일 부여 -> 정상
            jdbc.execute(
                    """
                    ALTER TABLE leave_balance
                    ADD CONSTRAINT chk_batch_employee_isolation
                    CHECK (total_granted <= 1.00)
                    """
            );
            checkAdded = true;

            // ① 첫 번째 배치 실행
            leaveService.batchGrantMonthlyLeave(today);

            Long firstGrantCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM grant_history
                     WHERE employee_id = ?
                       AND grant_type = 'ANNUAL_MONTHLY'
                    """,
                    Long.class,
                    EMPLOYEE_ID
            );

            Long secondGrantCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM grant_history
                     WHERE employee_id = ?
                       AND grant_type = 'ANNUAL_MONTHLY'
                    """,
                    Long.class,
                    secondEmployeeId
            );

            // A 직원은 실패했으므로 발생 이력도 롤백
            assertEquals(0L, firstGrantCount);

            // B 직원은 독립 트랜잭션으로 정상 커밋
            assertEquals(1L, secondGrantCount);

            BigDecimal firstTotal = jdbc.queryForObject(
                    """
                    SELECT total_granted
                      FROM leave_balance
                     WHERE employee_id = ?
                       AND year = ?
                    """,
                    BigDecimal.class,
                    EMPLOYEE_ID,
                    YEAR
            );

            BigDecimal secondTotal = jdbc.queryForObject(
                    """
                    SELECT total_granted
                      FROM leave_balance
                     WHERE employee_id = ?
                       AND year = ?
                    """,
                    BigDecimal.class,
                    secondEmployeeId,
                    YEAR
            );

            assertEquals(
                    0,
                    new BigDecimal("1.00").compareTo(firstTotal)
            );

            assertEquals(
                    0,
                    new BigDecimal("1.00").compareTo(secondTotal)
            );

        } finally {

            if (checkAdded) {
                jdbc.execute(
                        """
                        ALTER TABLE leave_balance
                        DROP CHECK chk_batch_employee_isolation
                        """
                );
            }
        }

        try {
            // ② 장애 원인을 제거한 뒤 재실행
            leaveService.batchGrantMonthlyLeave(today);

            Long firstGrantCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM grant_history
                     WHERE employee_id = ?
                       AND grant_type = 'ANNUAL_MONTHLY'
                    """,
                    Long.class,
                    EMPLOYEE_ID
            );

            Long secondGrantCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM grant_history
                     WHERE employee_id = ?
                       AND grant_type = 'ANNUAL_MONTHLY'
                    """,
                    Long.class,
                    secondEmployeeId
            );

            // A는 10월 1일, 11월 1일 두 건이 발생한다.
            assertEquals(2L, firstGrantCount);

            // B는 이미 부여되었으므로 여전히 한 건
            assertEquals(1L, secondGrantCount);

            BigDecimal firstTotal = jdbc.queryForObject(
                    """
                    SELECT total_granted
                      FROM leave_balance
                     WHERE employee_id = ?
                       AND year = ?
                    """,
                    BigDecimal.class,
                    EMPLOYEE_ID,
                    YEAR
            );

            BigDecimal secondTotal = jdbc.queryForObject(
                    """
                    SELECT total_granted
                      FROM leave_balance
                     WHERE employee_id = ?
                       AND year = ?
                    """,
                    BigDecimal.class,
                    secondEmployeeId,
                    YEAR
            );

            // A: 기존 1일 + 새로 발생한 2일
            assertEquals(
                    0,
                    new BigDecimal("3.00").compareTo(firstTotal)
            );

            // B: 중복 증가 없이 1일 유지
            assertEquals(
                    0,
                    new BigDecimal("1.00").compareTo(secondTotal)
            );

            // ③ 다시 실행해도 중복 부여되지 않는다.
            leaveService.batchGrantMonthlyLeave(today);

            assertEquals(
                    2L,
                    jdbc.queryForObject(
                            """
                            SELECT COUNT(*)
                              FROM grant_history
                             WHERE employee_id = ?
                               AND grant_type = 'ANNUAL_MONTHLY'
                            """,
                            Long.class,
                            EMPLOYEE_ID
                    )
            );

            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            """
                            SELECT COUNT(*)
                              FROM grant_history
                             WHERE employee_id = ?
                               AND grant_type = 'ANNUAL_MONTHLY'
                            """,
                            Long.class,
                            secondEmployeeId
                    )
            );

        } finally {

            // 이번 테스트에서 추가한 두 번째 직원의 데이터 정리
            jdbc.update(
                    "DELETE FROM grant_history WHERE employee_id = ?",
                    secondEmployeeId
            );

            jdbc.update(
                    "DELETE FROM leave_balance WHERE employee_id = ?",
                    secondEmployeeId
            );
        }
    }





}
