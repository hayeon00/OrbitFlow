
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
    // 1. 회사 기본 근태 규칙
    // ==========================================

    public DefaultRuleResDto getDefaultRule(Long companyId) {
        return new DefaultRuleResDto(
                findDefaultRuleOrThrow(companyId)
        );
    }

    @Transactional
    public DefaultRuleResDto updateDefaultRule(
            Long companyId,
            DefaultRuleUpdateReqDto request
    ) {
        AttendanceRule rule = findDefaultRuleOrThrow(companyId);

        rule.updateRule(
                request.defaultStartTime(),
                request.defaultEndTime(),
                request.defaultBreakMinutes()
        );

        return new DefaultRuleResDto(rule);
    }

    // ==========================================
    // 2. 사원별 예외 근태 규칙 조회
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
    // 3. 사원별 예외 근태 규칙 생성
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

        // 2. 사원 조회 및 비관적 락 획득
        // 같은 사원에 대한 규칙 변경을 직렬화
        Employee employee = findEmployeeForRuleUpdate(
                companyId,
                request.employeeId()
        );

        // 3. 기존 활성 예외 규칙과 기간 중복 검사
        validateNoOverlappingRule(
                companyId,
                employee.getId(),
                request.validFrom(),
                request.validTo(),
                null
        );

        // 4. 엔티티 생성 (자체 유효성 검증 포함)
        EmployeeRule rule = EmployeeRule.create(
                companyId,
                employee.getId(),
                request.startTime(),
                request.endTime(),
                request.breakMinutes(),
                request.reason(),
                request.validFrom(),
                request.validTo()
        );

        // 5. 저장
        EmployeeRule savedRule =
                employeeRuleRepository.save(rule);

        // 6. 알림 생성
        notificationService.createNotification(
                companyId,
                employee.getId(),
                NotificationType.ATTENDANCE,
                "새로운 근태 예외 규칙이 적용되었습니다. 적용 시작일: "
                        + request.validFrom(),
                "/"
        );

        // 7. 응답 DTO 반환
        return toEmployeeRuleResDto(savedRule, employee);
    }

    // ==========================================
    // 4. 사원별 예외 근태 규칙 수정
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

        // 2. 날짜 검증
        validateRuleDates(
                request.validFrom(),
                request.validTo()
        );

        // 3. 사원 잠금
        Employee employee = findEmployeeForRuleUpdate(
                companyId,
                rule.getEmployeeId()
        );

        // 4. 잠금 이후 최신 규칙 상태 조회
        entityManager.refresh(
                rule,
                LockModeType.PESSIMISTIC_WRITE
        );

        // 5. 다시 소속 검증
        validateCompanyAccess(
                companyId,
                rule.getCompanyId()
        );

        if (!rule.getEmployeeId().equals(employee.getId())) {
            throw new BusinessException(
                    "규칙의 사원 정보가 일치하지 않습니다."
            );
        }

        // 6. 수정 후 활성화 여부 결정
        boolean activeAfterUpdate =
                request.isActive() != null
                        ? request.isActive()
                        : Boolean.TRUE.equals(rule.getIsActive());

        // 7. 활성 규칙인 경우 중복 검증
        // 현재 수정 중인 규칙은 제외
        if (activeAfterUpdate) {
            validateNoOverlappingRule(
                    companyId,
                    rule.getEmployeeId(),
                    request.validFrom(),
                    request.validTo(),
                    rule.getId()
            );
        }

        // 8. 엔티티 변경
        rule.updateRule(
                request.startTime(),
                request.endTime(),
                request.breakMinutes(),
                request.reason(),
                request.validFrom(),
                request.validTo(),
                request.isActive()
        );

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
    // 5. 사원별 예외 근태 규칙 삭제
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

        // 사원 잠금
        Employee employee = findEmployeeForRuleUpdate(
                companyId,
                rule.getEmployeeId()
        );

        // 최신 규칙 상태 확인
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
    // 6. 근태 규칙 중복 검증
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
    // 7. 유효성 검증
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

    private void validateCompanyAccess(
            Long currentCompanyId,
            Long targetCompanyId
    ) {
        if (currentCompanyId == null ||
                !currentCompanyId.equals(targetCompanyId)) {
            throw new BusinessException(
                    "해당 데이터에 접근할 권한이 없습니다."
            );
        }
    }

    // ==========================================
    // 8. 사원 조회
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
    // 9. 근태 규칙 조회
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
    // 10. 응답 DTO 변환
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
    // 11. 회사 기본 근태 규칙 초기화
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
                .isDefault(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        attendanceRuleRepository.save(defaultRule);
    }
}
