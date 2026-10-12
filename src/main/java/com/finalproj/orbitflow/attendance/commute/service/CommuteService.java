package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.approval.attendance.event.entity.AttendanceEvent;
import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.attendance.commute.dto.ActiveRuleResDto;
import com.finalproj.orbitflow.attendance.commute.dto.TodayAttResDto;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.global.time.attendance.TimeProvider;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.enums.WorkStatus;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import com.finalproj.orbitflow.global.exception.BusinessException; // 전역 예외 처리기와 연동
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Please explain the class!!!
 *
 * @author : rlagkdus
 * @filename : CommuteService
 * @since : 2025. 12. 17. 수요일
 */


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommuteService {

    private final CommuteRepository commuteRepository;
    private final AttendanceRuleRepository attendanceRuleRepository;
    private final EmployeeRuleRepository employeeRuleRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceEventRepository attendanceEventRepository;
    private final TimeProvider timeProvider;


    public ActiveRuleResDto getActiveRule(Long companyId, Long employeeId) {
        return getActiveRule(
                companyId,
                employeeId,
                timeProvider.today()
        );
    }

    private ActiveRuleResDto getActiveRule(Long companyId, Long employeeId, LocalDate workDate) {
        return employeeRuleRepository.findActiveRuleByEmployeeIdAndDate(
                        employeeId,
                        workDate
                )
                .map(rule -> new ActiveRuleResDto(
                        rule.getStartTime(),
                        rule.getEndTime(),
                        "EXCEPTION"
                ))
                .orElseGet(() -> attendanceRuleRepository
                        .findByCompanyIdAndIsDefaultTrue(companyId)
                        .map(rule -> new ActiveRuleResDto(
                                rule.getDefaultStartTime(),
                                rule.getDefaultEndTime(),
                                "DEFAULT"
                        ))
                        .orElse(new ActiveRuleResDto(
                                LocalTime.of(9, 0),
                                LocalTime.of(18, 0),
                                "SYSTEM"
                        )));
    }


    public TodayAttResDto getTodayAttendance(Long companyId, Long employeeId) {
        LocalDate today = timeProvider.today();
        Attendance attendance = commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId,
                        employeeId,
                        today
                )
                .orElse(null);
        Employee employee = findEmployee(employeeId);

        return new TodayAttResDto(attendance, employee.getWorkStatus() == WorkStatus.AWAY);
    }



    @Transactional
    public TodayAttResDto checkIn(Long companyId, Long employeeId, AttendanceStatus forcedStatus) {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalDate workDate = now.toLocalDate();

        ActiveRuleResDto rule = getActiveRule(
                companyId,
                employeeId,
                workDate
        );

        AttendanceStatus status = forcedStatus != null
                ? forcedStatus
                : determineAttendanceStatus(
                now.toLocalTime(),
                rule.getStartTime()
        );

        commuteRepository.insertCheckInIgnoreDuplicate(
                companyId,
                employeeId,
                workDate,
                now,
                status.name()
        );

        Attendance attendance = commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId,
                        employeeId,
                        workDate
                )
                .orElseThrow(() ->
                        new BusinessException(
                                "출근 기록을 불러올 수 없습니다."
                        )
                );


        if (attendance.getLeaveAt() != null) {
            throw new BusinessException(
                    "이미 퇴근 처리된 근태 기록입니다."
            );
        }

        Employee employee = findEmployee(employeeId);

        if (employee.getWorkStatus() != WorkStatus.AWAY) {
            updateEmployeeWorkStatus(
                    employee,
                    WorkStatus.WORKING
            );
        }

        return new TodayAttResDto(
                attendance,
                employee.getWorkStatus() == WorkStatus.AWAY
        );
    }


    @Transactional
    public TodayAttResDto checkOut(Long companyId, Long employeeId) {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalDate workDate = now.toLocalDate();

        Attendance attendance = commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId,
                        employeeId,
                        workDate
                )
                .orElseThrow(() ->
                        new BusinessException("출근 기록이 없습니다.")
                );

        ActiveRuleResDto rule =
                getActiveRule(companyId, employeeId, workDate);

        validateCheckOutTime(
                now.toLocalTime(),
                rule.getEndTime()
        );

        attendance.recordLeave(now);
        updateEmployeeWorkStatus(employeeId, WorkStatus.OFF_WORK);

        return new TodayAttResDto(attendance, false);
    }

    private void validateCheckOutTime(LocalTime now, LocalTime endTimeThreshold) {
        LocalTime normalizedNow = now.withNano(0);
        LocalTime normalizedEndTime = endTimeThreshold.withNano(0);

        if (normalizedNow.isBefore(normalizedEndTime)) {
            throw new BusinessException(
                    "퇴근 시간(" + normalizedEndTime
                            + ") 이전이라서 퇴근할 수 없습니다."
            );
        }
    }


    @Transactional
    public void startAway(Long companyId, Long employeeId) {
        LocalDate workDate = timeProvider.today();

        validateAttendanceRecord(
                companyId,
                employeeId,
                workDate
        );

        Employee employee = findEmployee(employeeId);

        if (employee.getWorkStatus() == WorkStatus.AWAY) {
            throw new BusinessException("이미 자리비움 상태입니다.");
        }

        updateEmployeeWorkStatus(employee, WorkStatus.AWAY);
    }


    @Transactional
    public void endAway(Long companyId, Long employeeId) {

        Employee emplyee = findEmployee(employeeId);

        if(!emplyee.getCompany().getId().equals(companyId)) {
            throw new BusinessException("다른 회사의 사원입니다.");
        }

        if(emplyee.getWorkStatus() != WorkStatus.AWAY) {
            throw new BusinessException("자리비움 상태에서만 업무에 복귀할 수 있습니다.");

        }
        updateEmployeeWorkStatus(
                employeeId,
                WorkStatus.WORKING
        );
    }


    private Employee findEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException("사원 정보를 찾을 수 없습니다."));
    }

    private void updateEmployeeWorkStatus(Long employeeId, WorkStatus status) {
        findEmployee(employeeId).updateWorkStatus(status);
    }

    private void updateEmployeeWorkStatus(Employee employee, WorkStatus status) {
        employee.updateWorkStatus(status);
    }

    private void validateAttendanceRecord(Long companyId, Long employeeId, LocalDate workDate) {
        commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId,
                        employeeId,
                        workDate
                )
                .orElseThrow(() ->
                        new BusinessException(
                                "출근 기록이 없습니다. 자리비움은 출근 후에 가능합니다."
                        )
                );
    }

    private AttendanceStatus determineAttendanceStatus(LocalTime now, LocalTime startTimeThreshold) {
        return now.withNano(0).isAfter(startTimeThreshold.withNano(0))
                ? AttendanceStatus.LATE : AttendanceStatus.ON_TIME;
    }


    @Transactional(readOnly = true)
    public WorkStatus getEmployeeWorkStatus(Long companyId, Long employeeId) {

        Employee employee = findEmployee(employeeId);

        if (!employee.getCompany().getId().equals(companyId)) {
            throw new BusinessException("다른 회사의 사원입니다.");
        }

        return employee.getWorkStatus();
    }

    @Transactional
    public void returnFromOutsideOrTrip(Long companyId, Long employeeId) {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalDate workDate = now.toLocalDate();

        AttendanceEvent event = attendanceEventRepository
                .findActiveEvent(employeeId, workDate)
                .orElseThrow(() ->
                        new NotFoundException(
                                "현재 진행 중인 출장/외근 기록이 없습니다."
                        )
                );

        Attendance attendance = commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId,
                        employeeId,
                        workDate
                )
                .orElseGet(() ->
                        Attendance.createForReturn(
                                companyId,
                                employeeId,
                                workDate
                        )
                );

        if (attendance.getCommuteAt() == null) {
            attendance.updateTimeByAdmin(now, null);
        }

        attendance.updateStatusAutomatically(
                AttendanceStatus.ON_TIME
        );

        commuteRepository.save(attendance);
        updateEmployeeWorkStatus(employeeId, WorkStatus.WORKING);
        event.updateEndDate(workDate.minusDays(1));
    }









}