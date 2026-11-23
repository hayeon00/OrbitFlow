package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.entity.AttendanceRecord;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.approval.document.enums.DocumentStatus;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.leave.enums.LeaveAttendancePolicy;
import com.finalproj.orbitflow.attendance.leave.service.LeaveTimePolicyService;
import com.finalproj.orbitflow.attendance.commute.dto.ActiveRuleResDto;
import com.finalproj.orbitflow.attendance.commute.dto.TodayAttResDto;
import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.enums.AttendanceStatus;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.service.EffectiveAttendanceRuleService;
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
import java.util.List;

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
    private final EmployeeRepository employeeRepository;
    private final AttendanceEventRepository attendanceEventRepository;
    private final TimeProvider timeProvider;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final LeaveTimePolicyService leaveTimePolicyService;
    private final EffectiveAttendanceRuleService effectiveAttendanceRuleService;



    /**
     * 오늘 적용되는 실제 근무 규칙 조회.
     */
    public ActiveRuleResDto getActiveRule(
            Long companyId,
            Long employeeId
    ) {
        return getActiveRule(
                companyId,
                employeeId,
                timeProvider.today()
        );
    }

    /**
     * 지정 날짜에 적용되는 실제 근무 규칙 조회.
     *
     * 회사 기본 규칙과 사원별 예외 규칙의
     * 우선순위를 공통 서비스에서 일관되게 처리한다.
     */
    private ActiveRuleResDto getActiveRule(
            Long companyId,
            Long employeeId,
            LocalDate workDate
    ) {

        EffectiveAttendanceRule rule =
                effectiveAttendanceRuleService.resolve(
                        companyId,
                        employeeId,
                        workDate
                );

        return new ActiveRuleResDto(
                rule.workStartTime(),
                rule.workEndTime(),
                rule.employeeOverride() ? "EXCEPTION" : "DEFAULT"
        );
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

        LocalTime currentTime = now.toLocalTime();
        LocalTime startTime = rule.getStartTime();

        // 추가: 승인된 종일 출장·외근 출근 차단
        validateNotOnApprovedOutsideWork(
                companyId,
                employeeId,
                workDate
        );


        if (currentTime.isBefore(startTime)) {
            throw new BusinessException(
                    "출근 시간 이전에는 출근할 수 없습니다."
            );
        }

        LocalTime attendanceStartTime = rule.getStartTime();

        // 승인된 오전반차라면 실제 복귀 기준시각으로 지각 여부를 판정
        boolean approvedMorningHalfDay =
                hasApprovedHalfDay(companyId, employeeId, workDate, "오전반차");

        if (approvedMorningHalfDay) {
            LeaveTimePolicy policy = leaveTimePolicyService.getPolicy(companyId);
            attendanceStartTime = policy.getMorningHalfReturnTime();
        }

        AttendanceStatus status = forcedStatus != null
                ? forcedStatus
                : determineAttendanceStatus(currentTime, attendanceStartTime);

        commuteRepository.insertCheckInIgnoreDuplicate(
                companyId,
                employeeId,
                workDate,
                now,
                status.name()
        );

        // 이미 VACATION 등으로 생성된 빈 근태 행은 중복 INSERT가 무시된다.
        // 최종 승인된 오전/오후 반차에 한해서만 빈 출근시간을 보완한다.
        // 일반 근무일 및 전일 휴가 기록에는 이 보정 로직을 적용하지 않는다.
        boolean approvedHalfDay = approvedMorningHalfDay
                || hasApprovedHalfDay(companyId, employeeId, workDate, "오후반차");

        if (approvedHalfDay) {
            commuteRepository.updateMissingCheckIn(
                    companyId,
                    employeeId,
                    workDate,
                    now,
                    status
            );
        }

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
    public TodayAttResDto checkOut(
            Long companyId,
            Long employeeId
    ) {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalDate workDate = now.toLocalDate();

        // 추가: 승인된 종일 출장·외근 퇴근 차단
        validateNotOnApprovedOutsideWork(
                companyId,
                employeeId,
                workDate
        );

        ActiveRuleResDto rule =
                getActiveRule(companyId, employeeId, workDate);

        LocalTime allowedCheckOutTime = rule.getEndTime();

        // 승인된 오후반차라면 회사에서 설정한 반차 퇴근시각을 적용
        if (hasApprovedHalfDay(companyId, employeeId, workDate, "오후반차")) {
            LeaveTimePolicy policy = leaveTimePolicyService.getPolicy(companyId);
            allowedCheckOutTime = policy.getAfternoonHalfLeaveTime();
        }

        validateCheckOutTime(now.toLocalTime(), allowedCheckOutTime);

        int updated = commuteRepository.updateLeaveAtIfNull(
                companyId,
                employeeId,
                workDate,
                now
        );

        if (updated == 0) {
            throw new BusinessException(
                    "출근 기록이 없거나 이미 퇴근 처리되었습니다."
            );
        }

        updateEmployeeWorkStatus(
                employeeId,
                WorkStatus.OFF_WORK
        );

        // JPQL 벌크 UPDATE는 영속성 컨텍스트를
        // 자동으로 동기화하지 않으므로 새로 조회
        Attendance attendance = commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId,
                        employeeId,
                        workDate
                )
                .orElseThrow(() ->
                        new BusinessException(
                                "근태 기록을 찾을 수 없습니다."
                        )
                );

        return new TodayAttResDto(attendance, false);
    }


    /**
     * 해당 날짜에 최종 승인된 오전/오후 반차가 있는지 확인한다.
     * 승인 대기 또는 반려된 신청은 출퇴근 기준에 영향을 주지 않는다.
     */
    private boolean hasApprovedHalfDay(
            Long companyId,
            Long employeeId,
            LocalDate workDate,
            String targetTypeName
    ) {
        List<AttendanceRecord> approvedLeaves =
                attendanceRecordRepository.findOverlappingRecords(
                        companyId,
                        employeeId,
                        DocumentStatus.APPROVED,
                        workDate,
                        workDate
                );

        long halfDayCount = approvedLeaves.stream()
                .filter(record -> record.getLeaveType() != null)
                .filter(record -> record.getLeaveType().getAttendancePolicy()
                        == LeaveAttendancePolicy.PARTIAL_DAY)
                .filter(record -> "오전반차".equals(record.getLeaveType().getTypeName())
                        || "오후반차".equals(record.getLeaveType().getTypeName()))
                .count();

        if (halfDayCount > 1) {
            throw new BusinessException(
                    "같은 날짜에 승인된 반차 기록이 중복되어 출퇴근 기준을 결정할 수 없습니다."
            );
        }

        return approvedLeaves.stream()
                .anyMatch(record -> record.getLeaveType() != null
                        && record.getLeaveType().getAttendancePolicy()
                        == LeaveAttendancePolicy.PARTIAL_DAY
                        && targetTypeName.equals(record.getLeaveType().getTypeName()));
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

        validateNotOnApprovedOutsideWork(
                companyId,
                employeeId,
                workDate
        );


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



    /**
     * 승인된 종일 출장·외근 기간에는
     * 일반 출퇴근 처리를 허용하지 않는다.
     *
     * 직원의 현재 WorkStatus가 아니라
     * 실제 승인된 날짜별 기록을 기준으로 판정한다.
     */
    private void validateNotOnApprovedOutsideWork(
            Long companyId,
            Long employeeId,
            LocalDate workDate
    ) {

        boolean hasApprovedWorkEvent =
                attendanceEventRepository.existsApprovedFullDayWorkEvent(
                        companyId,
                        employeeId,
                        workDate
                );

        if (hasApprovedWorkEvent) {
            throw new BusinessException(
                    "승인된 출장 또는 종일 외근 기간에는 " +
                            "일반 출퇴근을 처리할 수 없습니다."
            );
        }
    }









}