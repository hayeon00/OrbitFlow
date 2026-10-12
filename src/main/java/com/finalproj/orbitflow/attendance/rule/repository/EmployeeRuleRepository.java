
package com.finalproj.orbitflow.attendance.rule.repository;

import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmployeeRuleRepository
        extends JpaRepository<EmployeeRule, Long> {

    @Query("""
        SELECT r
        FROM EmployeeRule r
        WHERE r.employeeId = :employeeId
          AND r.isActive = true
          AND r.validFrom <= :targetDate
          AND (r.validTo IS NULL OR r.validTo >= :targetDate)
        ORDER BY r.validFrom DESC
    """)
    Optional<EmployeeRule> findActiveRuleByEmployeeIdAndDate(
            @Param("employeeId") Long employeeId,
            @Param("targetDate") LocalDate targetDate
    );

    List<EmployeeRule> findByCompanyIdAndIsActiveTrueOrderByAppliedAtDesc(
            Long companyId
    );


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT r
    FROM EmployeeRule r
    WHERE r.companyId = :companyId
      AND r.employeeId = :employeeId
      AND r.isActive = true
      AND (:excludeRuleId IS NULL OR r.id <> :excludeRuleId)
      AND (:newValidTo IS NULL OR r.validFrom <= :newValidTo)
      AND (r.validTo IS NULL OR r.validTo >= :newValidFrom)
""")
    List<EmployeeRule> findOverlappingRulesForUpdate(
            @Param("companyId") Long companyId,
            @Param("employeeId") Long employeeId,
            @Param("newValidFrom") LocalDate newValidFrom,
            @Param("newValidTo") LocalDate newValidTo,
            @Param("excludeRuleId") Long excludeRuleId
    );

}
