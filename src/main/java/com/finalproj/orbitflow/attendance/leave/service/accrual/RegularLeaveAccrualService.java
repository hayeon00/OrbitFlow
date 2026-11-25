
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.attendance.leave.service.AnnualAttendanceService;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 입사 1주년 이후 정기 연차 발생 (직원 1명 단위).
 *
 * 적용 대상:
 * - 재직 중인 입사 1년 이상 사원
 * - 정확한 입사 기념일을 발생일로 사용
 * - 직전 1년간 출근율 80% 이상
 *
 * 연간 근태 집계는 AnnualAttendanceService,
 * 출근율·부여 일수 판단은 AnnualLeaveAccrualPolicy,
 * 저장은 LeaveGrantWriter가 담당한다.
 *
 * 80% 미만 직원의 월별 개근 연차는 아직 구현되지 않았다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegularLeaveAccrualService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final EmployeeRepository employeeRepository;
    private final LeaveGrantRepository leaveGrantRepository;
    private final AnnualAttendanceService annualAttendanceService;
    private final AnnualLeaveAccrualPolicy annualLeaveAccrualPolicy;
    private final LeaveGrantWriter leaveGrantWriter;

    @Transactional
    public void grantRegularAnnualLeave(
            Long employeeId,
            LocalDate grantDate
    ) {
        Objects.requireNonNull(employeeId, "사원 ID가 없습니다.");
        Objects.requireNonNull(grantDate, "정기 연차 발생일이 없습니다.");

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new NotFoundException("사원을 찾을 수 없습니다.")
                );

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            return;
        }

        LocalDate hireDate = employee.getHireDate();

        if (hireDate == null) {
            log.warn("[정기 연차] 입사일 누락 - employeeId={}", employeeId);
            return;
        }

        // 미래 발생일에 대한 선지급 차단
        if (grantDate.isAfter(LocalDate.now(KOREA))) {
            throw new IllegalArgumentException(
                    "미래 날짜의 정기 연차는 지급할 수 없습니다."
            );
        }

        if (!AccrualSchedule.isRegularGrantDate(hireDate, grantDate)) {
            throw new IllegalArgumentException(
                    "정기 연차 발생일은 입사 1주년 이후의 "
                            + "정확한 입사 기념일이어야 합니다."
            );
        }

        // 같은 직원, 같은 유형, 같은 발생일의 중복 지급 방지
        if (leaveGrantRepository.existsByEmployeeIdAndGrantTypeAndGrantDate(
                employeeId,
                LeaveGrantTypes.REGULAR,
                grantDate
        )) {
            // 배치가 다음 기념일 전까지 매일 재시도하므로 정상 경로다.
            log.debug(
                    "[정기 연차] 이미 부여됨 - employeeId={}, grantDate={}",
                    employeeId,
                    grantDate
            );
            return;
        }

        Long companyId = employee.getCompany().getId();

        // 직전 1년의 실제 연간 근태 집계
        AnnualAttendanceService.AnnualAttendanceSummary summary =
                annualAttendanceService.evaluate(
                        companyId,
                        employeeId,
                        AccrualSchedule.regularPeriodStart(hireDate, grantDate),
                        grantDate.minusDays(1)
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

        switch (decision.status()) {

            case UNDETERMINED -> {
                // 근태 미확정: 지급 보류 (다음 배치에서 재평가)
                log.info(
                        "[정기 연차 보류] 근태 미확정 - employeeId={}, date={}, "
                                + "undeterminedDays={}",
                        employeeId,
                        grantDate,
                        summary.undeterminedDays()
                );
            }

            case BELOW_80_PERCENT -> {
                // 80% 미만: 정기 연차(15일 이상)는 지급하지 않음
                log.info(
                        "[정기 연차 미부여] 출근율 80% 미만 - "
                                + "employeeId={}, date={}, attended={}, scheduled={}",
                        employeeId,
                        grantDate,
                        summary.attendedDays(),
                        summary.scheduledDays()
                );
            }

            case ELIGIBLE -> {
                leaveGrantWriter.grant(
                        companyId,
                        employeeId,
                        grantDate,
                        BigDecimal.valueOf(decision.grantDays()),
                        LeaveGrantTypes.REGULAR,
                        AccrualSchedule.regularExpirationDate(grantDate)
                );

                log.info(
                        "[정기 연차 부여 완료] employeeId={}, grantDate={}, "
                                + "yearsOfService={}, grantDays={}",
                        employeeId,
                        grantDate,
                        decision.yearsOfService(),
                        decision.grantDays()
                );
            }
        }
    }
}
