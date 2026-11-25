
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.attendance.leave.enums.MonthlyAttendanceResult;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import com.finalproj.orbitflow.attendance.leave.service.MonthlyAttendanceService;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 입사 1년 미만 직원의 월별 연차 발생 (직원 1명 단위).
 *
 * 입사 후 매월 같은 날, 직전 1개월이 개근(PERFECT)이면 1일을 지급한다.
 * 사용 기한은 입사 1년차 마지막 날이다.
 *
 * 개근 판정은 MonthlyAttendanceService,
 * 저장은 LeaveGrantWriter가 담당한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyLeaveAccrualService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final EmployeeRepository employeeRepository;
    private final LeaveGrantRepository leaveGrantRepository;
    private final MonthlyAttendanceService monthlyAttendanceService;
    private final LeaveGrantWriter leaveGrantWriter;

    /**
     * 기준일까지 발생일이 도래한 월별 연차를 모두 평가해 지급한다.
     *
     * 배치에서 직원마다 호출하며, 호출 1회가 트랜잭션 1개다.
     * 매일 지난 달을 다시 평가하므로 서버 중단이나 근태 미확정으로
     * 보류된 달도 입사 1년차 안에서는 다음 실행 때 다시 처리된다.
     * 입사 1년이 지나면 소급 지급하지 않는다.
     */
    @Transactional
    public void grantDueMonthlyLeaves(
            Long employeeId,
            LocalDate today
    ) {
        Objects.requireNonNull(today, "연차 부여 기준일은 필수입니다.");

        Employee employee = findEmployee(employeeId);

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            return;
        }

        LocalDate hireDate = employee.getHireDate();

        if (!AccrualSchedule.isInFirstYear(hireDate, today)) {
            return;
        }

        // 지급 후 영속성 컨텍스트가 비워져 employee가 준영속이 되므로
        // 루프에서는 식별값만 사용한다.
        Long companyId = employee.getCompany().getId();

        for (int month = 1;
             month <= AccrualSchedule.MONTHLY_GRANT_COUNT;
             month++) {

            LocalDate grantDate =
                    AccrualSchedule.monthlyGrantDate(hireDate, month);

            if (grantDate.isAfter(today)) {
                break;
            }

            grantIfPerfect(companyId, employeeId, hireDate, month, today);
        }
    }

    /**
     * 특정 발생일 1건의 월별 연차 발생.
     *
     * grantDate가 입사일 기준 월별 발생일이 아니면 아무 것도 하지 않는다.
     */
    @Transactional
    public void grantMonthlyLeave(
            Long employeeId,
            LocalDate grantDate
    ) {
        Employee employee = findEmployee(employeeId);

        LocalDate hireDate = employee.getHireDate();

        if (employee.getStatus() != EmployeeStatus.ACTIVE
                || hireDate == null
                || grantDate == null) {
            return;
        }

        for (int month = 1;
             month <= AccrualSchedule.MONTHLY_GRANT_COUNT;
             month++) {

            if (AccrualSchedule.monthlyGrantDate(hireDate, month)
                    .equals(grantDate)) {

                grantIfPerfect(
                        employee.getCompany().getId(),
                        employeeId,
                        hireDate,
                        month,
                        LocalDate.now(KOREA)
                );
                return;
            }
        }
    }

    /**
     * month번째 월별 연차: 중복 검사 → 개근 평가 → 저장.
     */
    private void grantIfPerfect(
            Long companyId,
            Long employeeId,
            LocalDate hireDate,
            int month,
            LocalDate today
    ) {
        LocalDate grantDate =
                AccrualSchedule.monthlyGrantDate(hireDate, month);

        LocalDate expirationDate =
                AccrualSchedule.firstYearEnd(hireDate);

        if (grantDate.isAfter(today)
                || today.isAfter(expirationDate)) {
            return;
        }

        if (leaveGrantRepository.existsByEmployeeIdAndGrantTypeAndGrantDate(
                employeeId,
                LeaveGrantTypes.MONTHLY,
                grantDate
        )) {
            return;
        }

        MonthlyAttendanceResult result =
                monthlyAttendanceService.evaluate(
                        companyId,
                        employeeId,
                        AccrualSchedule.monthlyPeriodStart(hireDate, month),
                        grantDate.minusDays(1)
                );

        if (result != MonthlyAttendanceResult.PERFECT) {
            log.info(
                    "[연차 발생 보류] employeeId={}, date={}, result={}",
                    employeeId,
                    grantDate,
                    result
            );
            return;
        }

        leaveGrantWriter.grant(
                companyId,
                employeeId,
                grantDate,
                BigDecimal.ONE,
                LeaveGrantTypes.MONTHLY,
                expirationDate
        );
    }

    private Employee findEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "사원을 찾을 수 없습니다. employeeId="
                                        + employeeId
                        ));
    }
}
