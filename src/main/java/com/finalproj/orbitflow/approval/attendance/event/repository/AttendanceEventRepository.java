package com.finalproj.orbitflow.approval.attendance.event.repository;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Please explain the class!!!
 *
 * @author : Choi MinHyeok
 * @filename : AttendanceEventRepository
 * @since : 26. 1. 1. 목요일
 **/


public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {
    boolean existsByEmployee_IdAndBaseRoleInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long employeeId,
            List<BaseRole> roles,
            LocalDate endDate,
            LocalDate startDate
    );



    /**
     * 현재 유효한 승인 출장·외근 기록만 조회한다.
     *
     * - 출장 또는 외근만 허용
     * - 최종 승인된 결재 문서만 허용
     * - 실제 조기 종료일이 있으면 우선 반영
     */
    @Query("""
    SELECT e
    FROM AttendanceEvent e
    JOIN e.sourceDocument d
    WHERE e.employee.id = :employeeId
      AND e.baseRole IN (
          com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole.BUSINESS_TRIP,
          com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole.OUTWORK
      )
      AND d.status =
          com.finalproj.orbitflow.approval.document.enums.DocumentStatus.APPROVED
      AND :today BETWEEN e.startDate
                     AND COALESCE(e.actualEndDate, e.endDate)
    """)
    Optional<AttendanceEvent> findActiveEvent(
            @Param("employeeId") Long employeeId,
            @Param("today") LocalDate today
    );



    @Query("""
    SELECT e
    FROM AttendanceEvent e
    JOIN e.sourceDocument d
    WHERE e.company.id = :companyId
      AND e.employee.id = :employeeId
      AND e.baseRole IN :roles
      AND d.status = :approvedStatus
      AND e.startDate <= :endDate
      AND COALESCE(e.actualEndDate, e.endDate) >= :startDate
    ORDER BY e.startDate ASC
    """)
    List<AttendanceEvent> findApprovedOverlappingWorkEvents(
            @Param("companyId") Long companyId,
            @Param("employeeId") Long employeeId,
            @Param("roles") List<BaseRole> roles,
            @Param("approvedStatus") DocumentStatus approvedStatus,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );


    /**
     * 기준일에 유효한 승인된 출장·외근 기록 조회.
     *
     * 실제 종료일(actualEndDate)이 있으면 해당 날짜를 우선한다.
     * 승인되지 않은 결재 문서는 제외한다.
     */
    @Query("""
    SELECT e
    FROM AttendanceEvent e
    JOIN FETCH e.employee emp
    JOIN e.sourceDocument doc
    WHERE e.baseRole IN :roles
      AND doc.status = :status
      AND e.startDate <= :today
      AND COALESCE(e.actualEndDate, e.endDate) >= :today
    """)
    List<AttendanceEvent> findApprovedActiveWorkEventsForDailySync(
            @Param("roles") List<BaseRole> roles,
            @Param("status") DocumentStatus status,
            @Param("today") LocalDate today
    );


    /**
     * 해당 날짜에 승인된 종일 출장·외근이 있는지 확인한다.
     *
     * 출퇴근 API 차단 및 화면 상태 판단에 사용.
     */
    @Query("""
    SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END
    FROM AttendanceEvent e
    JOIN e.sourceDocument d
    WHERE e.company.id = :companyId
      AND e.employee.id = :employeeId
      AND e.baseRole IN (
          com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole.BUSINESS_TRIP,
          com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole.OUTWORK
      )
      AND d.status =
          com.finalproj.orbitflow.approval.document.enums.DocumentStatus.APPROVED
      AND e.startDate <= :workDate
      AND COALESCE(e.actualEndDate, e.endDate) >= :workDate
    """)
    boolean existsApprovedFullDayWorkEvent(
            @Param("companyId") Long companyId,
            @Param("employeeId") Long employeeId,
            @Param("workDate") LocalDate workDate
    );



}
