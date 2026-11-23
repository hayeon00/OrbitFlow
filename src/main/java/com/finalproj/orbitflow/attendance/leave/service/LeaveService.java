
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;
import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.entity.Document;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.dto.request.LeaveSearchReqDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveBalanceResDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveHistoryResDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveRemainingResDto;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveBalance;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveType;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.enums.WorkStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class LeaveService {

    private static final ZoneId KOREA =
            ZoneId.of("Asia/Seoul");

    private static final String MONTHLY =
            "ANNUAL_MONTHLY";

    private static final String REGULAR =
            "ANNUAL_REGULAR";

    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final LeaveGrantRepository leaveGrantRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final CommuteRepository commuteRepository;
    private final MonthlyAttendanceService monthlyAttendanceService;
    private final LeaveUsageAllocationService leaveUsageAllocationService;
    private final AttendanceEventRepository attendanceEventRepository;
    private final AnnualAttendanceService annualAttendanceService;
    private final AnnualLeaveAccrualPolicy annualLeaveAccrualPolicy;
    private final PlatformTransactionManager transactionManager;



    /**
     * 입사 1년 미만 직원의 월별 연차 일괄 부여.
     *
     * 직원별로 독립 트랜잭션을 사용한다.
     * 한 직원의 저장 실패는 다른 직원의 부여에 영향을 주지 않는다.
     *
     * 기존 개근 판정, 발생일 계산, 중복 방지,
     * 원자적 잔여량 증가 로직은 그대로 사용한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void batchGrantMonthlyLeave(LocalDate today) {

        if (today == null) {
            throw new IllegalArgumentException(
                    "연차 부여 기준일은 필수입니다."
            );
        }

        TransactionTemplate employeeTransaction =
                new TransactionTemplate(transactionManager);

        employeeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );

        for (Company company : companyRepository.findAll()) {

            Long companyId = company.getId();

            List<Employee> employees =
                    employeeRepository.findByCompanyIdAndStatus(
                            companyId,
                            EmployeeStatus.ACTIVE
                    );

            for (Employee candidate : employees) {

                Long employeeId = candidate.getId();

                try {
                    employeeTransaction.executeWithoutResult(
                            status -> {

                                // 트랜잭션 내부에서 다시 조회해
                                // 관리 상태의 Employee를 사용한다.
                                Employee employee =
                                        employeeRepository.findById(employeeId)
                                                .orElseThrow(() ->
                                                        new IllegalStateException(
                                                                "연차 부여 대상 사원이 없습니다. "
                                                                        + "employeeId="
                                                                        + employeeId
                                                        )
                                                );

                                if (employee.getStatus()
                                        != EmployeeStatus.ACTIVE) {
                                    return;
                                }

                                LocalDate hireDate =
                                        employee.getHireDate();

                                if (hireDate == null
                                        || hireDate.isAfter(today)) {
                                    return;
                                }

                                LocalDate expirationDate =
                                        hireDate.plusYears(1).minusDays(1);

                                // 기존 정책 유지:
                                // 입사 1년이 지난 후에는
                                // 과거 월별 연차를 소급 지급하지 않는다.
                                if (today.isAfter(expirationDate)) {
                                    return;
                                }

                                for (int month = 1; month <= 11; month++) {

                                    LocalDate grantDate =
                                            getMonthlyGrantDate(
                                                    hireDate,
                                                    month
                                            );

                                    if (grantDate.isAfter(today)) {
                                        break;
                                    }

                                    if (grantDate.isAfter(expirationDate)) {
                                        break;
                                    }

                                    // 실제 개근 평가와 발생 이력 저장은
                                    // 직원별 트랜잭션 안에서 수행한다.
                                    grantMonthlyLeaveInternal(
                                            employee,
                                            month,
                                            today
                                    );
                                }
                            }
                    );

                } catch (RuntimeException e) {

                    // 해당 직원 트랜잭션은 이미 롤백됐다.
                    // 다음 직원은 새로운 트랜잭션으로 계속 처리한다.
                    log.error(
                            "[월별 연차 일괄 부여 실패] "
                                    + "companyId={}, employeeId={}, today={}",
                            companyId,
                            employeeId,
                            today,
                            e
                    );
                }
            }
        }
    }


    /**
     * 개별 사원 월별 연차 발생.
     */
    @Transactional
    public void grantMonthlyLeave(
            Long employeeId,
            LocalDate grantDate
    ) {

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        if (employee.getStatus() != EmployeeStatus.ACTIVE
                || employee.getHireDate() == null
                || grantDate == null) {
            return;
        }

        for (int month = 1; month <= 11; month++) {

            if (getMonthlyGrantDate(
                    employee.getHireDate(),
                    month
            ).equals(grantDate)) {

                grantMonthlyLeaveInternal(
                        employee,
                        month,
                        LocalDate.now(KOREA)
                );

                return;
            }
        }
    }

    /**
     * 실제 월별 연차 발생 처리.
     */
    private void grantMonthlyLeaveInternal(
            Employee employee,
            int month,
            LocalDate today
    ) {

        LocalDate hire = employee.getHireDate();

        LocalDate grantDate =
                getMonthlyGrantDate(hire, month);

        LocalDate expiry =
                hire.plusYears(1).minusDays(1);

        if (grantDate.isAfter(today)
                || today.isAfter(expiry)) {
            return;
        }

        boolean alreadyGranted =
                leaveGrantRepository
                        .existsByEmployeeIdAndGrantTypeAndGrantDate(
                                employee.getId(),
                                MONTHLY,
                                grantDate
                        );

        if (alreadyGranted) {
            return;
        }

        LocalDate periodStart =
                getMonthlyGrantDate(hire, month - 1);

        LocalDate periodEnd =
                grantDate.minusDays(1);

        MonthlyAttendanceResult result =
                monthlyAttendanceService.evaluate(
                        employee.getCompany().getId(),
                        employee.getId(),
                        periodStart,
                        periodEnd
                );

        if (result != MonthlyAttendanceResult.PERFECT) {

            log.info(
                    "[연차 발생 보류] employeeId={}, date={}, result={}",
                    employee.getId(),
                    grantDate,
                    result
            );

            return;
        }

        saveGrantAndBalance(
                employee,
                grantDate,
                BigDecimal.ONE,
                MONTHLY,
                expiry
        );
    }

    /**
     * 입사일 기준 월별 발생 예정일 계산.
     */
    public LocalDate getMonthlyGrantDate(
            LocalDate hireDate,
            int completedMonths
    ) {

        if (hireDate == null
                || completedMonths < 0
                || completedMonths > 11) {

            throw new IllegalArgumentException(
                    "입사일 또는 완료 개월 수가 올바르지 않습니다."
            );
        }

        return hireDate.plusMonths(completedMonths);
    }

    /**
     * 기존 회계연도 일괄 지급 방식은 사용하지 않음.
     */
    @Deprecated
    public void batchGrantAnnualLeave(
            Long companyId,
            Integer year
    ) {
        throw new IllegalStateException(
                "회계연도 일괄 부여는 폐지했습니다. "
                        + "연간 출근율 평가 서비스 연결이 필요합니다."
        );
    }


    /**
     * 입사일 기준 정기 연차 발생.
     *
     * 적용 대상:
     * - 재직 중인 입사 1년 이상 사원
     * - 정확한 입사 기념일
     * - 직전 1년간 출근율 80% 이상
     *
     * 주의:
     * - 80% 미만 직원의 월별 개근 연차는 별도 처리 대상
     * - 스케줄러와 연결하지 않은 개별 발생 메서드
     */
    @Transactional
    public void grantRegularAnnualLeave(
            Long employeeId,
            LocalDate grantDate
    ) {
        Objects.requireNonNull(
                employeeId,
                "사원 ID가 없습니다."
        );

        Objects.requireNonNull(
                grantDate,
                "정기 연차 발생일이 없습니다."
        );

        Employee employee = employeeRepository
                .findById(employeeId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "사원을 찾을 수 없습니다."
                        )
                );

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            return;
        }

        LocalDate hireDate = employee.getHireDate();

        if (hireDate == null) {
            log.warn(
                    "[정기 연차] 입사일 누락 - employeeId={}",
                    employeeId
            );
            return;
        }

        // 미래 발생일에 대한 선지급 차단
        LocalDate today = LocalDate.now(KOREA);

        if (grantDate.isAfter(today)) {
            throw new IllegalArgumentException(
                    "미래 날짜의 정기 연차는 지급할 수 없습니다."
            );
        }

        // 정확한 입사 기념일인지 검증
        int yearsOfService =
                grantDate.getYear() - hireDate.getYear();

        if (yearsOfService < 1
                || !hireDate.plusYears(yearsOfService)
                .equals(grantDate)) {

            throw new IllegalArgumentException(
                    "정기 연차 발생일은 입사 1주년 이후의 "
                            + "정확한 입사 기념일이어야 합니다."
            );
        }

        // 같은 직원, 같은 유형, 같은 발생일의 중복 지급 방지
        boolean alreadyGranted = leaveGrantRepository
                .existsByEmployeeIdAndGrantTypeAndGrantDate(
                        employeeId,
                        REGULAR,
                        grantDate
                );

        if (alreadyGranted) {
            log.info(
                    "[정기 연차] 이미 부여됨 - employeeId={}, grantDate={}",
                    employeeId,
                    grantDate
            );
            return;
        }

        Long companyId = employee.getCompany().getId();

        // 직전 1년의 평가 기간
        LocalDate periodStart =
                hireDate.plusYears(yearsOfService - 1);

        LocalDate periodEnd =
                grantDate.minusDays(1);

        // 실제 연간 근태 집계
        AnnualAttendanceService.AnnualAttendanceSummary summary =
                annualAttendanceService.evaluate(
                        companyId,
                        employeeId,
                        periodStart,
                        periodEnd
                );

        // 80% 출근율 및 근속연수별 발생 일수 계산
        AnnualLeaveAccrualPolicy.Decision decision =
                annualLeaveAccrualPolicy.evaluate(
                        hireDate,
                        grantDate,
                        summary.scheduledDays(),
                        summary.attendedDays(),
                        summary.undeterminedDays()
                );

        // 근태 미확정: 지급 보류
        if (decision.status()
                == AnnualLeaveAccrualPolicy.Status.UNDETERMINED) {

            log.info(
                    "[정기 연차 보류] 근태 미확정 - employeeId={}, date={}, "
                            + "undeterminedDays={}",
                    employeeId,
                    grantDate,
                    summary.undeterminedDays()
            );
            return;
        }

        // 80% 미만: 정기 연차 15일 이상은 지급하지 않음
        // 월별 개근 연차는 추후 별도 흐름으로 처리한다.
        if (decision.status()
                == AnnualLeaveAccrualPolicy.Status.BELOW_80_PERCENT) {

            log.info(
                    "[정기 연차 미부여] 출근율 80% 미만 - "
                            + "employeeId={}, date={}, attended={}, scheduled={}",
                    employeeId,
                    grantDate,
                    summary.attendedDays(),
                    summary.scheduledDays()
            );
            return;
        }

        if (decision.status()
                != AnnualLeaveAccrualPolicy.Status.ELIGIBLE) {

            throw new IllegalStateException(
                    "알 수 없는 정기 연차 평가 결과입니다."
            );
        }

        if (decision.grantDays() <= 0) {
            throw new IllegalStateException(
                    "정기 연차 발생 일수가 올바르지 않습니다."
            );
        }

        BigDecimal grantDays =
                BigDecimal.valueOf(decision.grantDays());

        // 입사일 기준 정기 연차의 사용 기한
        LocalDate expirationDate =
                grantDate.plusYears(1).minusDays(1);

        // 기존 월별 연차와 동일한 저장 경로 사용
        // grant_history 저장 + leave_balance 원자적 증가
        saveGrantAndBalance(
                employee,
                grantDate,
                grantDays,
                REGULAR,
                expirationDate
        );

        log.info(
                "[정기 연차 부여 완료] employeeId={}, grantDate={}, "
                        + "yearsOfService={}, grantDays={}",
                employeeId,
                grantDate,
                yearsOfService,
                decision.grantDays()
        );
    }



    /**
     * 발생 이력과 연도별 부여량을 동일 트랜잭션에서 저장한다.
     *
     * 발생 이력은 UNIQUE 제약조건으로 중복을 방지하고,
     * 연도별 부여량은 MySQL 원자적 증가 연산으로 갱신한다.
     */
    @Transactional
    protected void saveGrantAndBalance(
            Employee emp,
            LocalDate date,
            BigDecimal days,
            String type,
            LocalDate expiry
    ) {

        Long companyId = emp.getCompany().getId();
        Long employeeId = emp.getId();

        // 1. 실제 연차 발생 이력 저장
        LeaveGrant grant = LeaveGrant.issue(
                companyId,
                employeeId,
                date,
                days,
                type,
                expiry
        );

        // UNIQUE 제약조건 위반을 이 시점에서 확인한다.
        leaveGrantRepository.saveAndFlush(grant);

        // 2. 발생 이력 저장에 성공한 경우에만
        //    연도별 부여량과 잔여량을 원자적으로 증가시킨다.
        leaveBalanceRepository.addGrantedDaysAtomically(
                companyId,
                employeeId,
                date.getYear(),
                days
        );
    }


    private LeaveBalance createInitialBalance(
            Long companyId,
            Long employeeId,
            int year
    ) {

        return LeaveBalance.builder()
                .companyId(companyId)
                .employeeId(employeeId)
                .year(year)
                .totalGranted(BigDecimal.ZERO)
                .remainingDays(BigDecimal.ZERO)
                .build();
    }

    /**
     * 연차 요약 조회.
     */
    public LeaveBalanceResDto getMySummary(
            Long companyId,
            Long employeeId,
            Integer year
    ) {

        int targetYear = year == null
                ? LocalDate.now(KOREA).getYear()
                : year;

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        if (!employee.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException(
                    "회사 정보가 일치하지 않습니다."
            );
        }

        LeaveBalance balance =
                leaveBalanceRepository
                        .findByCompanyIdAndEmployeeIdAndYear(
                                companyId,
                                employeeId,
                                targetYear
                        )
                        .orElseGet(() ->
                                createInitialBalance(
                                        companyId,
                                        employeeId,
                                        targetYear
                                ));

        BigDecimal granted = balance.getTotalGranted();

        BigDecimal remaining =
                leaveGrantRepository.findAll().stream()
                        .filter(g ->
                                g.getCompanyId().equals(companyId))
                        .filter(g ->
                                g.getEmployeeId().equals(employeeId))
                        .filter(g ->
                                g.getGrantDate().getYear() == targetYear)
                        .filter(g ->
                                !Boolean.TRUE.equals(g.getIsExpired()))
                        .filter(g ->
                                !g.getExpirationDate().isBefore(
                                        LocalDate.now(KOREA)))
                        .map(LeaveGrant::getRemainingDays)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal used =
                attendanceRecordRepository
                        .findByCompanyIdAndEmployeeId(
                                companyId,
                                employeeId
                        )
                        .stream()
                        .filter(r ->
                                r.getStatus() == DocumentStatus.APPROVED)
                        .filter(r ->
                                r.getLeaveType() != null
                                        && Boolean.TRUE.equals(
                                        r.getLeaveType().getIsCountable()))
                        .filter(r ->
                                r.getStartDate().getYear() == targetYear)
                        .map(AttendanceRecord::getDays)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        return LeaveBalanceResDto.builder()
                .year(targetYear)
                .totalGranted(granted)
                .remainingDays(remaining)
                .usedDays(used)
                .hireDate(employee.getHireDate())
                .build();
    }

    /**
     * 전체 휴가 신청 내역 조회.
     */
    public Page<LeaveHistoryResDto> getAllLeaveHistory(
            Long companyId,
            Long employeeId,
            LeaveSearchReqDto searchDto,
            Pageable pageable
    ) {

        return attendanceRecordRepository
                .findAllLeaveHistoryWithFilters(
                        companyId,
                        employeeId,
                        searchDto.getTypeName(),
                        searchDto.getStatus(),
                        searchDto.getStartDate(),
                        searchDto.getEndDate(),
                        pageable
                )
                .map(this::mapRecordToDto);
    }

    /**
     * 휴가 사용 이력 조회.
     */
    public Page<LeaveHistoryResDto> getLeaveUsageHistory(
            Long companyId,
            Long employeeId,
            int year,
            String typeName,
            DocumentStatus status,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {

        return attendanceRecordRepository
                .findUsageHistoryWithFilters(
                        companyId,
                        employeeId,
                        year,
                        typeName,
                        status,
                        startDate,
                        endDate,
                        pageable
                )
                .map(this::mapRecordToDto);
    }

    private LeaveHistoryResDto mapRecordToDto(
            AttendanceRecord r
    ) {

        String typeName =
                r.getLeaveType() != null
                        ? r.getLeaveType().getTypeName()
                        : "미지정";

        String actionDate =
                r.getCreatedAt()
                        .atZone(KOREA)
                        .toLocalDate()
                        .toString();

        return LeaveHistoryResDto.builder()
                .title(typeName)
                .actionDate(actionDate)
                .period(
                        r.getStartDate()
                                + " ~ "
                                + (r.getEndDate() != null
                                ? r.getEndDate()
                                : r.getStartDate())
                )
                .days(r.getDays())
                .type("USED")
                .statusName(mapStatusText(r.getStatus()))
                .statusCode(r.getStatus().name())
                .reason(r.getReason())
                .typeDescription(
                        r.getLeaveType() != null
                                ? r.getLeaveType().getDescription()
                                : ""
                )
                .build();
    }

    private String mapStatusText(DocumentStatus status) {

        if (status == null) {
            return "대기";
        }

        return switch (status) {
            case APPROVED -> "승인";
            case REJECTED -> "반려";
            default -> "대기";
        };
    }

    /**
     * 기존 전자결재의 연차 차감 호출과 연결.
     *
     * Document와 AttendanceRecord가 모두
     * APPROVED로 저장된 뒤 실행해야 함.
     */
    @Transactional
    public void deduction(
            Employee employee,
            BigDecimal days,
            Document document,
            LeaveType leaveType
    ) {

        if (!Boolean.TRUE.equals(
                leaveType.getIsCountable())) {
            return;
        }

        if (document.getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException(
                    "최종 승인된 문서만 연차를 차감할 수 있습니다."
            );
        }

        AttendanceRecord record =
                attendanceRecordRepository
                        .findBySourceDocument_Id(document.getId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "승인 문서에 연결된 근태 기록이 없습니다: "
                                                + document.getId()
                                ));

        if (record.getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException(
                    "근태 기록의 승인 상태가 아직 반영되지 않았습니다."
            );
        }

        if (record.getEmployee() == null
                || !record.getEmployee().getId()
                .equals(employee.getId())
                || !record.getCompany().getId()
                .equals(employee.getCompany().getId())
                || record.getDays().compareTo(days) != 0
                || !record.getLeaveType().getId()
                .equals(leaveType.getId())) {

            throw new IllegalStateException(
                    "결재 요청과 근태 기록이 일치하지 않습니다."
            );
        }

        leaveUsageAllocationService.allocateApprovedLeave(
                record.getId()
        );
    }

    /**
     * 승인된 휴가의 차감 취소.
     */
    @Transactional
    public void releaseApprovedLeave(
            Long attendanceRecordId
    ) {
        leaveUsageAllocationService.releaseLeave(
                attendanceRecordId
        );
    }

    /**
     * 현재 사용 가능한 연차 잔여량.
     */
    public LeaveRemainingResDto getLeaveRemaining(
            Long employeeId
    ) {

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        BigDecimal remaining =
                leaveGrantRepository.findAll().stream()
                        .filter(g ->
                                g.getEmployeeId().equals(employeeId))
                        .filter(g ->
                                g.getCompanyId().equals(
                                        employee.getCompany().getId()))
                        .filter(g ->
                                !Boolean.TRUE.equals(g.getIsExpired()))
                        .filter(g ->
                                !g.getExpirationDate().isBefore(
                                        LocalDate.now(KOREA)))
                        .map(LeaveGrant::getRemainingDays)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        return new LeaveRemainingResDto(remaining);
    }

    /**
     * 사원의 근무 상태 변경.
     */
    @Transactional
    public void updateWorkStatus(
            Long employeeId,
            WorkStatus status
    ) {

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "사원을 찾을 수 없습니다."
                                ));

        employee.updateWorkStatus(status);
    }




    /**
     * 기준일의 승인된 휴가·출장·외근을 사원 근무상태에 반영한다.
     *
     * 처리 원칙:
     * 1. 이전 날짜의 특수 근무상태 초기화
     * 2. 승인된 종일 휴가 반영
     * 3. 승인된 출장·외근 반영
     * 4. 반차는 종일 VACATION으로 처리하지 않음
     *
     * 결재 승인, 일정 생성, 연차 차감에는 관여하지 않는다.
     */
    @Transactional
    public void updateAllEmployeesWorkStatus(LocalDate today) {

        Objects.requireNonNull(today, "기준일이 없습니다.");

        // 1. 이전 날짜 특수 근무상태 초기화
        List<WorkStatus> specialStatuses =
                getSpecialWorkStatuses();

        List<Employee> specialStatusEmployees =
                employeeRepository.findByStatusAndWorkStatusIn(
                        EmployeeStatus.ACTIVE,
                        specialStatuses
                );

        for (Employee employee : specialStatusEmployees) {
            employee.updateWorkStatus(WorkStatus.OFF_WORK);
        }

        // 2. 승인된 종일 휴가 처리
        List<AttendanceRecord> activeRecords =
                attendanceRecordRepository
                        .findActiveAttendanceRecords(today);

        for (AttendanceRecord record : activeRecords) {

            if (record.getStatus() != DocumentStatus.APPROVED) {
                continue;
            }

            Employee employee = record.getEmployee();

            if (employee == null
                    || employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }

            if (record.getLeaveType() == null
                    || record.getLeaveType().getAttendancePolicy()
                    == null) {
                log.warn(
                        "[근무상태 동기화] 휴가 유형 누락 - recordId={}",
                        record.getId()
                );
                continue;
            }

            LeaveAttendancePolicy policy =
                    record.getLeaveType().getAttendancePolicy();

            // 오전반차·오후반차·반반차는
            // 실제 근무시간이 존재하므로 건너뛴다.
            if (policy == LeaveAttendancePolicy.PARTIAL_DAY) {
                continue;
            }

            // 종일 근무 면제 휴가만 반영
            if (policy != LeaveAttendancePolicy.ATTENDANCE_CREDIT) {
                continue;
            }

            if (record.getSourceDocument() == null
                    || record.getSourceDocument()
                    .getTemplateGroup() == null) {
                continue;
            }

            BaseRole role =
                    record.getSourceDocument()
                            .getTemplateGroup()
                            .getBaseRole();

            if (role != BaseRole.VACATION) {
                continue;
            }

            employee.updateWorkStatus(WorkStatus.VACATION);
        }

        // 3. 승인된 출장·외근 기록 조회
        List<AttendanceEvent> workEvents =
                attendanceEventRepository
                        .findApprovedActiveWorkEventsForDailySync(
                                List.of(
                                        BaseRole.BUSINESS_TRIP,
                                        BaseRole.OUTWORK
                                ),
                                DocumentStatus.APPROVED,
                                today
                        );

        // 동일 사원의 복수 출장·외근 충돌 감지
        Map<Long, WorkStatus> resolvedStatuses = new HashMap<>();
        Set<Long> conflictingEmployeeIds = new HashSet<>();

        for (AttendanceEvent event : workEvents) {

            Employee employee = event.getEmployee();

            if (employee == null
                    || employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }

            // 종일 휴가와 충돌할 때 휴가 상태를 보호
            if (employee.getWorkStatus() == WorkStatus.VACATION) {
                log.warn(
                        "[근무상태 동기화] 휴가·출장외근 중복 - employeeId={}, date={}",
                        employee.getId(),
                        today
                );
                continue;
            }

            WorkStatus targetStatus =
                    switch (event.getBaseRole()) {
                        case BUSINESS_TRIP -> WorkStatus.BUSINESS_TRIP;
                        case OUTWORK -> WorkStatus.OUTWORK;
                        default -> null;
                    };

            if (targetStatus == null) {
                continue;
            }

            Long employeeId = employee.getId();

            WorkStatus previousStatus =
                    resolvedStatuses.putIfAbsent(
                            employeeId,
                            targetStatus
                    );

            if (previousStatus != null
                    && previousStatus != targetStatus) {

                conflictingEmployeeIds.add(employeeId);

                log.warn(
                        "[근무상태 동기화] 출장·외근 충돌 - employeeId={}, date={}",
                        employeeId,
                        today
                );
            }
        }

        // 4. 충돌하지 않는 출장·외근 상태 적용
        for (AttendanceEvent event : workEvents) {

            Employee employee = event.getEmployee();

            if (employee == null
                    || employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }

            Long employeeId = employee.getId();

            if (conflictingEmployeeIds.contains(employeeId)) {
                continue;
            }

            if (employee.getWorkStatus() == WorkStatus.VACATION) {
                continue;
            }

            WorkStatus targetStatus =
                    resolvedStatuses.get(employeeId);

            if (targetStatus != null) {
                employee.updateWorkStatus(targetStatus);
            }
        }

        log.info(
                "[근무상태 동기화] 완료 - date={}, vacations={}, workEvents={}, conflicts={}",
                today,
                activeRecords.size(),
                workEvents.size(),
                conflictingEmployeeIds.size()
        );
    }




    private List<WorkStatus> getSpecialWorkStatuses() {
        return List.of(
                WorkStatus.VACATION,
                WorkStatus.BUSINESS_TRIP,
                WorkStatus.OUTWORK
        );
    }

    public Employee getEmployeeById(Long employeeId) {

        return employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "ID [" + employeeId
                                        + "] 사원을 찾을 수 없습니다."
                        ));
    }

    /**
     * 회사별 일일 근태 상태 반영.
     */
    @Transactional
    public void normalizeTodayAttendanceStatus(
            Long companyId,
            LocalDate today
    ) {

        List<Employee> employees =
                employeeRepository.findByCompanyIdAndStatus(
                        companyId,
                        EmployeeStatus.ACTIVE
                );

        for (Employee employee : employees) {

            WorkStatus workStatus =
                    employee.getWorkStatus();

            if (workStatus == null) {
                continue;
            }

            AttendanceStatus targetStatus =
                    mapToAttendanceStatus(workStatus);

            if (targetStatus == null) {
                continue;
            }

            upsertTodayAttendance(
                    companyId,
                    employee.getId(),
                    today,
                    targetStatus
            );
        }
    }

    private AttendanceStatus mapToAttendanceStatus(
            WorkStatus workStatus
    ) {

        return switch (workStatus) {
            case VACATION -> AttendanceStatus.VACATION;
            case OUTWORK -> AttendanceStatus.OUTSIDE;
            case BUSINESS_TRIP -> AttendanceStatus.BUSINESS_TRIP;
            default -> null;
        };
    }


    /**
     * 기준일 근태 기록 생성 또는 자동 상태 반영.
     *
     * 원칙:
     * 1. 기존 출퇴근시간을 덮어쓰지 않는다.
     * 2. 관리자가 보정한 기록은 변경하지 않는다.
     * 3. 기존 근태 상태가 자동 생성된 특수 상태인 경우에만
     *    다른 특수 상태로 변경할 수 있다.
     */
    @Transactional
    protected void upsertTodayAttendance(
            Long companyId,
            Long employeeId,
            LocalDate workDate,
            AttendanceStatus targetStatus
    ) {

        Attendance attendance =
                commuteRepository
                        .findByCompanyIdAndEmployeeIdAndWorkDate(
                                companyId,
                                employeeId,
                                workDate
                        )
                        .orElse(null);

        if (attendance == null) {

            Attendance created =
                    Attendance.builder()
                            .companyId(companyId)
                            .employeeId(employeeId)
                            .workDate(workDate)
                            .commuteAt(null)
                            .leaveAt(null)
                            .isCorrected(false)
                            .status(targetStatus)
                            .build();

            commuteRepository.save(created);
            return;
        }

        // 관리자 수동 보정 기록 보호
        if (Boolean.TRUE.equals(attendance.getIsCorrected())) {
            return;
        }

        // 실제 출근·퇴근 기록 보호
        if (attendance.getCommuteAt() != null
                || attendance.getLeaveAt() != null) {
            return;
        }

        AttendanceStatus currentStatus =
                attendance.getStatus();

        // 이미 동일한 상태이면 변경 불필요
        if (currentStatus == targetStatus) {
            return;
        }

        // 기존 자동 생성 특수 상태만 다른 특수 상태로 갱신
        boolean automaticallyManagedStatus =
                currentStatus == AttendanceStatus.VACATION
                        || currentStatus == AttendanceStatus.OUTSIDE
                        || currentStatus == AttendanceStatus.BUSINESS_TRIP;

        if (!automaticallyManagedStatus) {
            log.warn(
                    "[근태 동기화] 기존 상태 보존 - companyId={}, "
                            + "employeeId={}, date={}, status={}, target={}",
                    companyId,
                    employeeId,
                    workDate,
                    currentStatus,
                    targetStatus
            );
            return;
        }

        attendance.updateStatusAutomatically(targetStatus);
    }

}
