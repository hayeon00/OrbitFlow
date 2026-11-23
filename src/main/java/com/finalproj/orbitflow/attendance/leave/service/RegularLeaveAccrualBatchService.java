
package com.finalproj.orbitflow.attendance.leave.service;

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

@Service
@RequiredArgsConstructor
@Slf4j
public class RegularLeaveAccrualBatchService {

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveService leaveService;

    /**
     * 입사일 기준 정기 연차 자동 부여.
     *
     * - ACTIVE 직원만 대상
     * - 입사 1주년 이후의 입사 기념일에만 실행
     * - 개별 지급은 LeaveService의 트랜잭션에서 처리
     * - 직원별 실패를 격리하여 다음 직원 계속 처리
     *
     * 이 메서드 자체에는 @Transactional을 적용하지 않는다.
     */
    public void run(LocalDate today) {

        Objects.requireNonNull(
                today,
                "연차 배치 기준일은 필수입니다."
        );

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

                try {
                    LocalDate hireDate = employee.getHireDate();

                    if (hireDate == null) {
                        continue;
                    }

                    int yearsOfService =
                            today.getYear() - hireDate.getYear();

                    if (yearsOfService < 1) {
                        continue;
                    }

                    // 정확한 입사 기념일에만 지급한다.
                    // 예: 2025-11-05 입사 → 2026-11-05 지급
                    if (!hireDate.plusYears(yearsOfService)
                            .equals(today)) {
                        continue;
                    }

                    // 실제 출근율 평가, 연차 일수 계산,
                    // 중복 방지, DB 저장은 기존 서비스에서 처리한다.
                    leaveService.grantRegularAnnualLeave(
                            employee.getId(),
                            today
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

        log.info(
                "[정기 연차 배치] 실행 완료 - today={}",
                today
        );
    }
}
