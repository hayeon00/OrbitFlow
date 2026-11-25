
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
 * 입사 1주년 이후 정기 연차 일괄 부여 (대상 선정 + 실패 격리).
 *
 * - ACTIVE 직원만 대상
 * - 가장 최근 입사 기념일(1주년 이후)을 발생일로 지급을 시도한다.
 *   기념일 당일 배치 누락(서버 중단·오류)이나 근태 미확정 보류가 있어도
 *   다음 기념일 전까지 매일 다시 평가되어 지급 기회를 잃지 않는다.
 * - 이미 지급된 경우 RegularLeaveAccrualService의 중복 검사에서 즉시 종료된다.
 * - 직원별 지급은 RegularLeaveAccrualService의 트랜잭션에서 처리
 * - 회사·직원 단위로 실패를 격리하여 다음 대상을 계속 처리
 *
 * 이 클래스에는 @Transactional을 적용하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegularLeaveAccrualBatchService {

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final RegularLeaveAccrualService regularLeaveAccrualService;

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
                        "[정기 연차 배치] 회사 직원 조회 실패 - companyId={}",
                        companyId,
                        e
                );
                continue;
            }

            for (Employee employee : employees) {

                LocalDate grantDate =
                        AccrualSchedule.latestRegularGrantDate(
                                employee.getHireDate(),
                                today
                        );

                if (grantDate == null) {
                    continue;
                }

                try {
                    regularLeaveAccrualService.grantRegularAnnualLeave(
                            employee.getId(),
                            grantDate
                    );

                } catch (RuntimeException e) {

                    // 해당 직원의 지급 트랜잭션이 실패해도
                    // 다음 직원의 지급은 계속 진행한다.
                    log.error(
                            "[정기 연차 배치] 직원별 지급 실패 - "
                                    + "companyId={}, employeeId={}, today={}",
                            companyId,
                            employee.getId(),
                            today,
                            e
                    );
                }
            }
        }

        log.info("[정기 연차 배치] 실행 완료 - today={}", today);
    }
}
