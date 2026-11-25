
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 입사 1년 미만 월별 연차 일괄 부여 (대상 선정 + 실패 격리).
 *
 * - ACTIVE 직원 중 입사 1년차 안에 있는 직원만 대상
 * - 직원별 지급은 MonthlyLeaveAccrualService의 트랜잭션에서 처리
 * - 회사·직원 단위로 실패를 격리하여 다음 대상을 계속 처리
 *
 * 이 클래스에는 @Transactional을 적용하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyLeaveAccrualBatchService {

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final MonthlyLeaveAccrualService monthlyLeaveAccrualService;

    public void run(LocalDate today) {

        Objects.requireNonNull(today, "연차 배치 기준일은 필수입니다.");

        for (Company company : companyRepository.findAll()) {

            Long companyId = company.getId();

            List<Employee> employees;

            try {
                employees =
                        employeeRepository.findByCompanyIdAndStatus(
                                companyId,
                                EmployeeStatus.ACTIVE
                        );

            } catch (RuntimeException e) {
                log.error(
                        "[월별 연차 배치] 회사 직원 조회 실패 - companyId={}",
                        companyId,
                        e
                );
                continue;
            }

            for (Employee employee : employees) {

                // 대상이 아닌 직원은 트랜잭션을 열지 않는다.
                // 최종 판정은 서비스에서 다시 한다.
                if (!AccrualSchedule.isInFirstYear(
                        employee.getHireDate(), today)) {
                    continue;
                }

                try {
                    monthlyLeaveAccrualService.grantDueMonthlyLeaves(
                            employee.getId(),
                            today
                    );

                } catch (RuntimeException e) {

                    // 해당 직원 트랜잭션은 이미 롤백됐다.
                    log.error(
                            "[월별 연차 배치] 직원별 지급 실패 - "
                                    + "companyId={}, employeeId={}, today={}",
                            companyId,
                            employee.getId(),
                            today,
                            e
                    );
                }
            }
        }

        log.info("[월별 연차 배치] 실행 완료 - today={}", today);
    }
}
