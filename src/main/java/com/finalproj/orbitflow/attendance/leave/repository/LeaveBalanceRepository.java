package com.finalproj.orbitflow.attendance.leave.repository;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

/**
 * Please explain the class!!!
 *
 * @author : rlagkdus
 * @filename : LeaveBalanceRepository
 * @since : 2025. 12. 24. 수요일
 */

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByCompanyIdAndEmployeeIdAndYear(Long companyId, Long employeeId, int year);

    Optional<LeaveBalance> findTopByEmployeeIdOrderByYearDesc(Long employeeId);

    List<LeaveBalance> findByEmployeeId(Long employeeId);


    /**
     * 연차 부여량을 MySQL에서 원자적으로 증가시킨다.
     *
     * 1. 해당 연도의 잔여량 행이 없다면 새로 생성한다.
     * 2. 이미 존재한다면 현재 DB 값에 부여 일수를 더한다.
     * 3. 동시 실행 시 UNIQUE 인덱스를 기준으로 같은 행을 갱신한다.
     *
     * uk_employee_year:
     * (company_id, employee_id, year)
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(value = """
        INSERT INTO leave_balance (
            company_id,
            employee_id,
            year,
            total_granted,
            remaining_days
        )
        VALUES (
            :companyId,
            :employeeId,
            :year,
            :days,
            :days
        )
        ON DUPLICATE KEY UPDATE
            total_granted = total_granted + :days,
            remaining_days = remaining_days + :days
        """, nativeQuery = true)
    int addGrantedDaysAtomically(
            @Param("companyId") Long companyId,
            @Param("employeeId") Long employeeId,
            @Param("year") Integer year,
            @Param("days") BigDecimal days
    );

}