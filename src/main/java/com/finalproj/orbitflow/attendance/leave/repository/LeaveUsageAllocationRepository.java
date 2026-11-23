package com.finalproj.orbitflow.attendance.leave.repository;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveUsageAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface LeaveUsageAllocationRepository
        extends JpaRepository<LeaveUsageAllocation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LeaveUsageAllocation a where a.attendanceRecordId = :recordId")
    List<LeaveUsageAllocation> findByRecordIdForUpdate(@Param("recordId") Long recordId);

    Optional<LeaveUsageAllocation> findByAttendanceRecordIdAndGrantHistoryId(
            Long attendanceRecordId, Long grantHistoryId);
}
