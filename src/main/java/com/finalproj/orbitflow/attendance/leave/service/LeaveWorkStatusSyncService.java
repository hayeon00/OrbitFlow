
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;
import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.approval.form.template.group.enums.BaseRole;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.EmployeeStatus;
import com.finalproj.orbitflow.hr.employee.enums.WorkStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 승인된 휴가·출장·외근을 사원 근무상태(WorkStatus)와
 * 일일 근태 기록(Attendance)에 반영하는 일일 동기화.
 *
 * 결재 승인, 연차 차감, 연차 발생에는 관여하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveWorkStatusSyncService {

    private final EmployeeRepository employeeRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceEventRepository attendanceEventRepository;
    private final CommuteRepository commuteRepository;

    /**
     * 기준일의 승인된 휴가·출장·외근을 사원 근무상태에 반영한다.
     *
     * 처리 원칙:
     * 1. 이전 날짜의 특수 근무상태 초기화
     * 2. 승인된 종일 휴가 반영
     * 3. 승인된 출장·외근 반영
     * 4. 반차는 종일 VACATION으로 처리하지 않음
     *
     * 결재 승인, 일정 생성, 연차 차감에는 관여하지 않는다.
     */
    @Transactional
    public void updateAllEmployeesWorkStatus(LocalDate today) {

        Objects.requireNonNull(today, "기준일이 없습니다.");

        // 1. 이전 날짜 특수 근무상태 초기화
        List<WorkStatus> specialStatuses =
                getSpecialWorkStatuses();

        List<Employee> specialStatusEmployees =
                employeeRepository.findByStatusAndWorkStatusIn(
                        EmployeeStatus.ACTIVE,
                        specialStatuses
                );

        for (Employee employee : specialStatusEmployees) {
            employee.updateWorkStatus(WorkStatus.OFF_WORK);
        }

        // 2. 승인된 종일 휴가 처리
        List<AttendanceRecord> activeRecords =
                attendanceRecordRepository
                        .findActiveAttendanceRecords(today);

        for (AttendanceRecord record : activeRecords) {

            if (record.getStatus() != DocumentStatus.APPROVED) {
                continue;
            }

            Employee employee = record.getEmployee();

            if (employee == null
                    || employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }

            if (record.getLeaveType() == null
                    || record.getLeaveType().getAttendancePolicy()
                    == null) {
                log.warn(
                        "[근무상태 동기화] 휴가 유형 누락 - recordId={}",
                        record.getId()
                );
                continue;
            }

            LeaveAttendancePolicy policy =
                    record.getLeaveType().getAttendancePolicy();

            // 오전반차·오후반차·반반차는
            // 실제 근무시간이 존재하므로 건너뛴다.
            if (policy == LeaveAttendancePolicy.PARTIAL_DAY) {
                continue;
            }

            // 종일 근무 면제 휴가만 반영
            if (policy != LeaveAttendancePolicy.ATTENDANCE_CREDIT) {
                continue;
            }

            if (record.getSourceDocument() == null
                    || record.getSourceDocument()
                    .getTemplateGroup() == null) {
                continue;
            }

            BaseRole role =
                    record.getSourceDocument()
                            .getTemplateGroup()
                            .getBaseRole();

            if (role != BaseRole.VACATION) {
                continue;
            }

            employee.updateWorkStatus(WorkStatus.VACATION);
        }

        // 3. 승인된 출장·외근 기록 조회
        List<AttendanceEvent> workEvents =
                attendanceEventRepository
                        .findApprovedActiveWorkEventsForDailySync(
                                List.of(
                                        BaseRole.BUSINESS_TRIP,
                                        BaseRole.OUTWORK
                                ),
                                DocumentStatus.APPROVED,
                                today
                        );

        // 동일 사원의 복수 출장·외근 충돌 감지
        Map<Long, WorkStatus> resolvedStatuses = new HashMap<>();
        Set<Long> conflictingEmployeeIds = new HashSet<>();

        for (AttendanceEvent event : workEvents) {

            Employee employee = event.getEmployee();

            if (employee == null
                    || employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }

            // 종일 휴가와 충돌할 때 휴가 상태를 보호
            if (employee.getWorkStatus() == WorkStatus.VACATION) {
                log.warn(
                        "[근무상태 동기화] 휴가·출장외근 중복 - employeeId={}, date={}",
                        employee.getId(),
                        today
                );
                continue;
            }

            WorkStatus targetStatus =
                    switch (event.getBaseRole()) {
                        case BUSINESS_TRIP -> WorkStatus.BUSINESS_TRIP;
                        case OUTWORK -> WorkStatus.OUTWORK;
                        default -> null;
                    };

            if (targetStatus == null) {
                continue;
            }

            Long employeeId = employee.getId();

            WorkStatus previousStatus =
                    resolvedStatuses.putIfAbsent(
                            employeeId,
                            targetStatus
                    );

            if (previousStatus != null
                    && previousStatus != targetStatus) {

                conflictingEmployeeIds.add(employeeId);

                log.warn(
                        "[근무상태 동기화] 출장·외근 충돌 - employeeId={}, date={}",
                        employeeId,
                        today
                );
            }
        }

        // 4. 충돌하지 않는 출장·외근 상태 적용
        for (AttendanceEvent event : workEvents) {

            Employee employee = event.getEmployee();

            if (employee == null
                    || employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }

            Long employeeId = employee.getId();

            if (conflictingEmployeeIds.contains(employeeId)) {
                continue;
            }

            if (employee.getWorkStatus() == WorkStatus.VACATION) {
                continue;
            }

            WorkStatus targetStatus =
                    resolvedStatuses.get(employeeId);

            if (targetStatus != null) {
                employee.updateWorkStatus(targetStatus);
            }
        }

        log.info(
                "[근무상태 동기화] 완료 - date={}, vacations={}, workEvents={}, conflicts={}",
                today,
                activeRecords.size(),
                workEvents.size(),
                conflictingEmployeeIds.size()
        );
    }




    private List<WorkStatus> getSpecialWorkStatuses() {
        return List.of(
                WorkStatus.VACATION,
                WorkStatus.BUSINESS_TRIP,
                WorkStatus.OUTWORK
        );
    }

    /**
     * 회사별 일일 근태 상태 반영.
     */
    @Transactional
    public void normalizeTodayAttendanceStatus(
            Long companyId,
            LocalDate today
    ) {

        List<Employee> employees =
                employeeRepository.findByCompanyIdAndStatus(
                        companyId,
                        EmployeeStatus.ACTIVE
                );

        for (Employee employee : employees) {

            WorkStatus workStatus =
                    employee.getWorkStatus();

            if (workStatus == null) {
                continue;
            }

            AttendanceStatus targetStatus =
                    mapToAttendanceStatus(workStatus);

            if (targetStatus == null) {
                continue;
            }

            upsertTodayAttendance(
                    companyId,
                    employee.getId(),
                    today,
                    targetStatus
            );
        }
    }

    private AttendanceStatus mapToAttendanceStatus(
            WorkStatus workStatus
    ) {

        return switch (workStatus) {
            case VACATION -> AttendanceStatus.VACATION;
            case OUTWORK -> AttendanceStatus.OUTSIDE;
            case BUSINESS_TRIP -> AttendanceStatus.BUSINESS_TRIP;
            default -> null;
        };
    }


    /**
     * 기준일 근태 기록 생성 또는 자동 상태 반영.
     *
     * 원칙:
     * 1. 기존 출퇴근시간을 덮어쓰지 않는다.
     * 2. 관리자가 보정한 기록은 변경하지 않는다.
     * 3. 기존 근태 상태가 자동 생성된 특수 상태인 경우에만
     *    다른 특수 상태로 변경할 수 있다.
     */
    private void upsertTodayAttendance(
            Long companyId,
            Long employeeId,
            LocalDate workDate,
            AttendanceStatus targetStatus
    ) {

        Attendance attendance =
                commuteRepository
                        .findByCompanyIdAndEmployeeIdAndWorkDate(
                                companyId,
                                employeeId,
                                workDate
                        )
                        .orElse(null);

        if (attendance == null) {

            Attendance created =
                    Attendance.builder()
                            .companyId(companyId)
                            .employeeId(employeeId)
                            .workDate(workDate)
                            .commuteAt(null)
                            .leaveAt(null)
                            .isCorrected(false)
                            .status(targetStatus)
                            .build();

            commuteRepository.save(created);
            return;
        }

        // 관리자 수동 보정 기록 보호
        if (Boolean.TRUE.equals(attendance.getIsCorrected())) {
            return;
        }

        // 실제 출근·퇴근 기록 보호
        if (attendance.getCommuteAt() != null
                || attendance.getLeaveAt() != null) {
            return;
        }

        AttendanceStatus currentStatus =
                attendance.getStatus();

        // 이미 동일한 상태이면 변경 불필요
        if (currentStatus == targetStatus) {
            return;
        }

        // 기존 자동 생성 특수 상태만 다른 특수 상태로 갱신
        boolean automaticallyManagedStatus =
                currentStatus == AttendanceStatus.VACATION
                        || currentStatus == AttendanceStatus.OUTSIDE
                        || currentStatus == AttendanceStatus.BUSINESS_TRIP;

        if (!automaticallyManagedStatus) {
            log.warn(
                    "[근태 동기화] 기존 상태 보존 - companyId={}, "
                            + "employeeId={}, date={}, status={}, target={}",
                    companyId,
                    employeeId,
                    workDate,
                    currentStatus,
                    targetStatus
            );
            return;
        }

        attendance.updateStatusAutomatically(targetStatus);
    }
}
