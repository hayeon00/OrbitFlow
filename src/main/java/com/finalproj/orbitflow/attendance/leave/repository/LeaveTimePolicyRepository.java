
package com.finalproj.orbitflow.attendance.leave.repository;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeaveTimePolicyRepository
        extends JpaRepository<LeaveTimePolicy, Long> {

    Optional<LeaveTimePolicy> findByCompanyId(
            Long companyId
    );
}
