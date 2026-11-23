
package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.dto.request.DefaultRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleCreateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.response.DefaultRuleResDto;
import com.finalproj.orbitflow.attendance.rule.dto.response.EmployeeRuleResDto;
import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;
import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.exception.BusinessException;
import com.finalproj.orbitflow.global.exception.InvalidRequestException;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import com.finalproj.orbitflow.notification.enums.NotificationType;
import com.finalproj.orbitflow.notification.service.NotificationCommandService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceRuleService {

    private final EmployeeRuleRepository employeeRuleRepository;
    private final AttendanceRuleRepository attendanceRuleRepository;
    private final EmployeeRepository employeeRepository;
    private final NotificationCommandService notificationService;

    @PersistenceContext
    private EntityManager entityManager;

    // ==========================================
    // 1. 회사 기본 근태 규칙 조회
    // ==========================================

    public DefaultRuleResDto getDefaultRule(Long companyId) {
        return new DefaultRuleResDto(
                findDefaultRuleOrThrow(companyId)
        );
    }

    // ==========================================
    // 2. 회사 기본 근태 규칙 수정
    // ==========================================

    @Transactional
    public DefaultRuleResDto updateDefaultRule(
            Long companyId,
            DefaultRuleUpdateReqDto request
    ) {
        AttendanceRule rule = findDefaultRuleOrThrow(companyId);

        LocalTime workStart = request.defaultStartTime() != null
                ? request.defaultStartTime()
                : rule.getDefaultStartTime();

        LocalTime workEnd = request.defaultEndTime() != null
                ? request.defaultEndTime()
                : rule.getDefaultEndTime();

        // 수정 요청에서 점심시간을 생략하면 기존 값 유지
        LocalTime lunchStart = request.breakStartTime() != null
                ? request.breakStartTime()
                : rule.getBreakStartTime();

        LocalTime lunchEnd = request.breakEndTime() != null
                ? request.breakEndTime()
                : rule.getBreakEndTime();

        try {
            validateLunchWithinWork(
                    workStart,
                    workEnd,
                    lunchStart,
                    lunchEnd
            );

            Integer breakMinutes = resolveBreakMinutes(
                    request.defaultBreakMinutes(),
                    rule.getDefaultBreakMinutes(),
                    lunchStart,
                    lunchEnd
            );

            rule.updateRule(
                    workStart,
                    workEnd,
                    breakMinutes
            );

            if (lunchStart != null && lunchEnd != null) {
                rule.updateLunchBreak(
                        lunchStart,
                        lunchEnd
                );
            }

        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        return new DefaultRuleResDto(rule);
    }

    // ==========================================
    // 3. 사원별 예외 근태 규칙 목록 조회
    // ==========================================

    public List<EmployeeRuleResDto> getExceptionRules(
            Long companyId
    ) {
        List<EmployeeRule> rules = employeeRuleRepository
                .findByCompanyIdAndIsActiveTrueOrderByAppliedAtDesc(
                        companyId
                );

        Map<Long, Employee> employeeMap = getEmployeeMap(rules);

        return rules.stream()
                .map(rule -> toEmployeeRuleResDto(
                        rule,
                        employeeMap.get(rule.getEmployeeId())
                ))
                .toList();
    }

    // ==========================================
    // 4. 사원별 예외 근태 규칙 상세 조회
    // ==========================================

    public EmployeeRuleResDto getExceptionRuleDetail(
            Long companyId,
            Long ruleId
    ) {
        EmployeeRule rule = findExceptionRuleOrThrow(ruleId);

        validateCompanyAccess(
                companyId,
                rule.getCompanyId()
        );

        Employee employee = findEmployeeOrThrow(
                rule.getEmployeeId()
        );

        return toEmployeeRuleResDto(rule, employee);
    }

    // ==========================================
    // 5. 사원별 예외 근태 규칙 생성
    // ==========================================

    @Transactional
    public EmployeeRuleResDto createExceptionRule(
            Long companyId,
            EmpAttRuleCreateReqDto request
    ) {
        // 1. 적용 기간 검증
        validateRuleDates(
                request.validFrom(),
                request.validTo()
        );

        // 2. 사원 잠금
        Employee employee = findEmployeeForRuleUpdate(
                companyId,
                request.employeeId()
        );

        // 3. 기존 활성 규칙과 기간 중복 검사
        validateNoOverlappingRule(
                companyId,
                employee.getId(),
                request.validFrom(),
                request.validTo(),
                null
        );

        // 4. 점심 구간 및 휴게시간 검증
        validateLunchWithinWork(
                request.startTime(),
                request.endTime(),
                request.breakStartTime(),
                request.breakEndTime()
        );

        Integer breakMinutes = resolveBreakMinutes(
                request.breakMinutes(),
                null,
                request.breakStartTime(),
                request.breakEndTime()
        );

        // 5. 엔티티 생성
        EmployeeRule rule;

        try {
            rule = EmployeeRule.create(
                    companyId,
                    employee.getId(),
                    request.startTime(),
                    request.endTime(),
                    breakMinutes,
                    request.reason(),
                    request.validFrom(),
                    request.validTo()
            );

            if (request.breakStartTime() != null) {
                rule.updateLunchBreak(
                        request.breakStartTime(),
                        request.breakEndTime()
                );
            }

        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        // 6. 저장
        EmployeeRule savedRule =
                employeeRuleRepository.save(rule);

        // 7. 알림 생성
        notificationService.createNotification(
                companyId,
                employee.getId(),
                NotificationType.ATTENDANCE,
                "새로운 근태 예외 규칙이 적용되었습니다. 적용 시작일: "
                        + request.validFrom(),
                "/"
        );

        // 8. 응답 반환
        return toEmployeeRuleResDto(savedRule, employee);
    }

    // ==========================================
    // 6. 사원별 예외 근태 규칙 수정
    // ==========================================

    @Transactional
    public EmployeeRuleResDto updateExceptionRule(
            Long companyId,
            Long ruleId,
            EmpAttRuleUpdateReqDto request
    ) {
        // 1. 수정 대상 조회
        EmployeeRule rule = findExceptionRuleOrThrow(ruleId);

        validateCompanyAccess(
                companyId,
                rule.getCompanyId()
        );

        // 2. 적용 기간 검증
        validateRuleDates(
                request.validFrom(),
                request.validTo()
        );

        // 3. 사원 잠금
        Employee employee = findEmployeeForRuleUpdate(
                companyId,
                rule.getEmployeeId()
        );

        // 4. 잠금 후 최신 상태 조회
        entityManager.refresh(
                rule,
                LockModeType.PESSIMISTIC_WRITE
        );

        validateCompanyAccess(
                companyId,
                rule.getCompanyId()
        );

        if (!rule.getEmployeeId().equals(employee.getId())) {
            throw new BusinessException(
                    "규칙의 사원 정보가 일치하지 않습니다."
            );
        }

        // 5. 수정 후 활성 여부 결정
        boolean activeAfterUpdate =
                request.isActive() != null
                        ? request.isActive()
                        : Boolean.TRUE.equals(rule.getIsActive());

        // 6. 활성 규칙이면 기간 중복 확인
        if (activeAfterUpdate) {
            validateNoOverlappingRule(
                    companyId,
                    rule.getEmployeeId(),
                    request.validFrom(),
                    request.validTo(),
                    rule.getId()
            );
        }

        // 7. 점심 구간 결정
        // 요청에서 빠진 필드는 기존 값을 사용
        LocalTime lunchStart = request.breakStartTime() != null
                ? request.breakStartTime()
                : rule.getBreakStartTime();

        LocalTime lunchEnd = request.breakEndTime() != null
                ? request.breakEndTime()
                : rule.getBreakEndTime();

        validateLunchWithinWork(
                request.startTime(),
                request.endTime(),
                lunchStart,
                lunchEnd
        );

        Integer breakMinutes = resolveBreakMinutes(
                request.breakMinutes(),
                rule.getBreakMinutes(),
                lunchStart,
                lunchEnd
        );

        // 8. 엔티티 수정
        try {
            rule.updateRule(
                    request.startTime(),
                    request.endTime(),
                    breakMinutes,
                    request.reason(),
                    request.validFrom(),
                    request.validTo(),
                    request.isActive()
            );

            if (lunchStart != null && lunchEnd != null) {
                rule.updateLunchBreak(
                        lunchStart,
                        lunchEnd
                );
            }

        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        // 9. 알림 생성
        notificationService.createNotification(
                companyId,
                employee.getId(),
                NotificationType.ATTENDANCE,
                "적용 중인 근태 예외 규칙이 수정되었습니다.",
                "/"
        );

        return toEmployeeRuleResDto(rule, employee);
    }

    // ==========================================
    // 7. 사원별 예외 근태 규칙 삭제
    // ==========================================

    @Transactional
    public void deleteExceptionRule(
            Long companyId,
            Long ruleId
    ) {
        EmployeeRule rule = findExceptionRuleOrThrow(ruleId);

        validateCompanyAccess(
                companyId,
                rule.getCompanyId()
        );

        Employee employee = findEmployeeForRuleUpdate(
                companyId,
                rule.getEmployeeId()
        );

        entityManager.refresh(
                rule,
                LockModeType.PESSIMISTIC_WRITE
        );

        validateCompanyAccess(
                companyId,
                rule.getCompanyId()
        );

        if (!rule.getEmployeeId().equals(employee.getId())) {
            throw new BusinessException(
                    "규칙의 사원 정보가 일치하지 않습니다."
            );
        }

        // 물리 삭제 대신 비활성화
        rule.delete();
    }

    // ==========================================
    // 8. 근태 규칙 적용 기간 중복 검사
    // ==========================================

    private void validateNoOverlappingRule(
            Long companyId,
            Long employeeId,
            LocalDate validFrom,
            LocalDate validTo,
            Long excludeRuleId
    ) {
        validateRuleDates(validFrom, validTo);

        boolean overlapping = !employeeRuleRepository
                .findOverlappingRulesForUpdate(
                        companyId,
                        employeeId,
                        validFrom,
                        validTo,
                        excludeRuleId
                )
                .isEmpty();

        if (overlapping) {
            throw new BusinessException(
                    "해당 사원에게 적용 기간이 겹치는 "
                            + "근태 예외 규칙이 존재합니다."
            );
        }
    }

    // ==========================================
    // 9. 점심 휴게시간 유효성 검증
    // ==========================================

    private void validateLunchWithinWork(
            LocalTime workStart,
            LocalTime workEnd,
            LocalTime lunchStart,
            LocalTime lunchEnd
    ) {
        // 기존 데이터와의 호환성:
        // 점심 구간이 둘 다 없으면 허용
        if (lunchStart == null && lunchEnd == null) {
            return;
        }

        // 한쪽만 있으면 저장 불가
        if (lunchStart == null || lunchEnd == null) {
            throw new InvalidRequestException(
                    "점심 시작·종료시간을 모두 입력해야 합니다."
            );
        }

        if (workStart == null || workEnd == null
                || !workEnd.isAfter(workStart)) {
            throw new InvalidRequestException(
                    "근무 시작·종료시간이 올바르지 않습니다."
            );
        }

        if (!lunchEnd.isAfter(lunchStart)) {
            throw new InvalidRequestException(
                    "점심 종료시간은 시작시간보다 늦어야 합니다."
            );
        }

        if (lunchStart.isBefore(workStart)
                || lunchEnd.isAfter(workEnd)) {
            throw new InvalidRequestException(
                    "점심시간은 근무시간 안에 있어야 합니다."
            );
        }

        long minutes = Duration.between(
                lunchStart,
                lunchEnd
        ).toMinutes();

        if (minutes <= 0 || minutes > 480) {
            throw new InvalidRequestException(
                    "점심 휴게시간은 1~480분이어야 합니다."
            );
        }
    }

    // ==========================================
    // 10. 휴게 분 수 계산
    // ==========================================

    private Integer resolveBreakMinutes(
            Integer requestedMinutes,
            Integer existingMinutes,
            LocalTime lunchStart,
            LocalTime lunchEnd
    ) {
        // 점심 구간이 없는 기존 규칙
        if (lunchStart == null && lunchEnd == null) {
            Integer minutes = requestedMinutes != null
                    ? requestedMinutes
                    : existingMinutes;

            validateBreakMinutes(minutes);
            return minutes;
        }

        if (lunchStart == null || lunchEnd == null) {
            throw new InvalidRequestException(
                    "점심 시작·종료시간을 모두 설정해야 합니다."
            );
        }

        long calculatedMinutes = Duration.between(
                lunchStart,
                lunchEnd
        ).toMinutes();

        if (calculatedMinutes <= 0 || calculatedMinutes > 480) {
            throw new InvalidRequestException(
                    "점심 휴게시간은 1~480분이어야 합니다."
            );
        }

        // 클라이언트가 분 수까지 보냈다면 구간과 일치해야 함
        if (requestedMinutes != null
                && requestedMinutes.longValue() != calculatedMinutes) {
            throw new InvalidRequestException(
                    "휴게 분 수와 점심시간 구간이 일치하지 않습니다."
            );
        }

        return Math.toIntExact(calculatedMinutes);
    }

    // ==========================================
    // 11. 기존 휴게 분 수 검증
    // ==========================================

    private void validateBreakMinutes(Integer minutes) {
        if (minutes != null && (minutes < 0 || minutes > 480)) {
            throw new InvalidRequestException(
                    "휴게 시간은 0~480분이어야 합니다."
            );
        }
    }

    // ==========================================
    // 12. 근태 규칙 적용 날짜 검증
    // ==========================================

    private void validateRuleDates(
            LocalDate from,
            LocalDate to
    ) {
        if (from == null) {
            throw new BusinessException(
                    "규칙 적용 시작일은 필수입니다."
            );
        }

        if (to != null && to.isBefore(from)) {
            throw new BusinessException(
                    "종료일은 시작일보다 빠를 수 없습니다."
            );
        }
    }

    // ==========================================
    // 13. 회사 접근 권한 검증
    // ==========================================

    private void validateCompanyAccess(
            Long currentCompanyId,
            Long targetCompanyId
    ) {
        if (currentCompanyId == null
                || !currentCompanyId.equals(targetCompanyId)) {
            throw new BusinessException(
                    "해당 데이터에 접근할 권한이 없습니다."
            );
        }
    }

    // ==========================================
    // 14. 사원 조회
    // ==========================================

    private Employee findEmployeeOrThrow(Long employeeId) {
        if (employeeId == null) {
            throw new BusinessException(
                    "사원 식별 번호가 누락되었습니다."
            );
        }

        return employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new BusinessException(
                                "사원 정보를 찾을 수 없습니다."
                        )
                );
    }

    // ==========================================
    // 15. 사원 잠금 조회
    // ==========================================

    private Employee findEmployeeForRuleUpdate(
            Long companyId,
            Long employeeId
    ) {
        if (employeeId == null) {
            throw new BusinessException(
                    "사원 식별 번호가 누락되었습니다."
            );
        }

        return employeeRepository
                .findByIdAndCompanyIdForUpdate(
                        employeeId,
                        companyId
                )
                .orElseThrow(() ->
                        new BusinessException(
                                "사원을 찾을 수 없거나 접근 권한이 없습니다."
                        )
                );
    }

    // ==========================================
    // 16. 회사 기본 규칙 조회
    // ==========================================

    private AttendanceRule findDefaultRuleOrThrow(
            Long companyId
    ) {
        return attendanceRuleRepository
                .findByCompanyIdAndIsDefaultTrue(companyId)
                .orElseThrow(() ->
                        new BusinessException(
                                "기본 근태 규칙이 설정되지 않은 회사입니다."
                        )
                );
    }

    // ==========================================
    // 17. 사원별 예외 규칙 조회
    // ==========================================

    private EmployeeRule findExceptionRuleOrThrow(
            Long ruleId
    ) {
        return employeeRuleRepository.findById(ruleId)
                .orElseThrow(() ->
                        new BusinessException(
                                "해당 예외 규칙을 찾을 수 없습니다."
                        )
                );
    }

    // ==========================================
    // 18. 사원별 규칙 응답 변환
    // ==========================================

    private EmployeeRuleResDto toEmployeeRuleResDto(
            EmployeeRule rule,
            Employee employee
    ) {
        return new EmployeeRuleResDto(
                rule,
                employee != null
                        ? employee.getName()
                        : "이름 미확인",
                employee != null
                        ? employee.getEmployeeNo()
                        : null
        );
    }

    // ==========================================
    // 19. 사원 정보 일괄 조회
    // ==========================================

    private Map<Long, Employee> getEmployeeMap(
            List<EmployeeRule> rules
    ) {
        List<Long> employeeIds = rules.stream()
                .map(EmployeeRule::getEmployeeId)
                .distinct()
                .toList();

        return employeeRepository
                .findAllByIdIn(employeeIds)
                .stream()
                .collect(Collectors.toMap(
                        Employee::getId,
                        employee -> employee
                ));
    }

    // ==========================================
    // 20. 신규 회사 기본 근태 규칙 생성
    // ==========================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createDefaultAttendanceRule(
            Company company
    ) {
        if (attendanceRuleRepository.existsByCompanyId(
                company.getId()
        )) {
            return;
        }

        AttendanceRule defaultRule = AttendanceRule.builder()
                .companyId(company.getId())
                .name("표준 근무 규칙")
                .defaultStartTime(LocalTime.of(9, 0))
                .defaultEndTime(LocalTime.of(18, 0))
                .defaultBreakMinutes(60)
                .breakStartTime(LocalTime.of(12, 0))
                .breakEndTime(LocalTime.of(13, 0))
                .isDefault(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        attendanceRuleRepository.save(defaultRule);
    }
}
