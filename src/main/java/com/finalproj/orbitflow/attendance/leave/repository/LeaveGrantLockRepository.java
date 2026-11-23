
package com.finalproj.orbitflow.attendance.leave.repository;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeaveGrantLockRepository
        extends JpaRepository<LeaveGrant, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT g
        FROM LeaveGrant g
        WHERE g.companyId = :companyId
          AND g.employeeId = :employeeId
        ORDER BY
          g.expirationDate ASC,
          g.grantDate ASC,
          g.id ASC
        """)
    List<LeaveGrant> lockEmployeeGrants(
            @Param("companyId") Long companyId,
            @Param("employeeId") Long employeeId
    );
}
