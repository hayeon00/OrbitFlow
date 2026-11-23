
package com.finalproj.orbitflow.attendance.rule.service;

import com.finalproj.orbitflow.attendance.rule.dto.response.EffectiveAttendanceRule;
import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;
import com.finalproj.orbitflow.attendance.rule.entity.EmployeeRule;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EffectiveAttendanceRuleService {

    private final AttendanceRuleRepository attendanceRuleRepository;
    private final EmployeeRuleRepository employeeRuleRepository;

    /**
     * 특정 날짜의 실제 적용 근무 규칙 조회.
     *
     * 우선순위:
     * 1. 사원별 활성 예외 규칙
     * 2. 회사 기본 근태 규칙
     */
    public EffectiveAttendanceRule resolve(
            Long companyId,
            Long employeeId,
            LocalDate workDate
    ) {
        if (companyId == null
                || employeeId == null
                || workDate == null) {
            throw new IllegalArgumentException(
                    "회사 ID, 사원 ID, 근무 날짜는 필수입니다."
            );
        }

        AttendanceRule companyRule =
                attendanceRuleRepository
                        .findByCompanyIdAndIsDefaultTrue(companyId)
                        .orElseThrow(() ->
                                new BusinessException(
                                        "회사 기본 근태 규칙이 없습니다."
                                )
                        );

        List<EmployeeRule> employeeRules =
                employeeRuleRepository.findEffectiveRules(
                        companyId,
                        employeeId,
                        workDate
                );

        if (employeeRules.size() > 1) {
            throw new BusinessException(
                    "해당 날짜에 적용되는 사원별 예외 규칙이 "
                            + "두 개 이상 존재합니다."
            );
        }

        // 사원별 예외 규칙이 없는 경우
        if (employeeRules.isEmpty()) {
            validateRule(
                    companyRule.getDefaultStartTime(),
                    companyRule.getDefaultEndTime(),
                    companyRule.getBreakStartTime(),
                    companyRule.getBreakEndTime(),
                    companyRule.getDefaultBreakMinutes()
            );

            return new EffectiveAttendanceRule(
                    companyId,
                    employeeId,
                    workDate,
                    companyRule.getDefaultStartTime(),
                    companyRule.getDefaultEndTime(),
                    companyRule.getBreakStartTime(),
                    companyRule.getBreakEndTime(),
                    companyRule.getDefaultBreakMinutes(),
                    false,
                    companyRule.getId()
            );
        }

        EmployeeRule employeeRule = employeeRules.get(0);

        LocalTime workStart = employeeRule.getStartTime();
        LocalTime workEnd = employeeRule.getEndTime();

        LocalTime breakStart = employeeRule.getBreakStartTime();
        LocalTime breakEnd = employeeRule.getBreakEndTime();

        Integer breakMinutes = employeeRule.getBreakMinutes();

        /*
         * 사원별 점심 구간이 없으면 회사 기본 구간을 사용.
         * 단, 회사 점심시간이 사원 근무시간 안에
         * 포함되어야 적용할 수 있다.
         */
        if (breakStart == null && breakEnd == null) {
            if (companyRule.getBreakStartTime() != null
                    && companyRule.getBreakEndTime() != null) {

                breakStart = companyRule.getBreakStartTime();
                breakEnd = companyRule.getBreakEndTime();

                breakMinutes = (int) Duration.between(
                        breakStart,
                        breakEnd
                ).toMinutes();

            } else {
                // 기존 데이터처럼 회사에도 점심 구간이 없는 경우
                // 사원별 휴게 분 수를 우선 사용
                if (breakMinutes == null) {
                    breakMinutes =
                            companyRule.getDefaultBreakMinutes();
                }
            }
        }

        validateRule(
                workStart,
                workEnd,
                breakStart,
                breakEnd,
                breakMinutes
        );

        return new EffectiveAttendanceRule(
                companyId,
                employeeId,
                workDate,
                workStart,
                workEnd,
                breakStart,
                breakEnd,
                breakMinutes,
                true,
                employeeRule.getId()
        );
    }

    /**
     * 실제 적용 규칙 검증.
     */
    private void validateRule(
            LocalTime workStart,
            LocalTime workEnd,
            LocalTime breakStart,
            LocalTime breakEnd,
            Integer breakMinutes
    ) {
        if (workStart == null || workEnd == null) {
            throw new BusinessException(
                    "출퇴근시간이 설정되지 않았습니다."
            );
        }

        if (!workEnd.isAfter(workStart)) {
            throw new BusinessException(
                    "퇴근시간은 출근시간보다 늦어야 합니다."
            );
        }

        if ((breakStart == null) != (breakEnd == null)) {
            throw new BusinessException(
                    "점심 시작·종료시간 설정이 불완전합니다."
            );
        }

        if (breakMinutes != null
                && (breakMinutes < 0 || breakMinutes > 480)) {
            throw new BusinessException(
                    "휴게시간은 0~480분이어야 합니다."
            );
        }

        if (breakStart == null) {
            // 기존 분 단위 규칙과 호환
            return;
        }

        if (!breakEnd.isAfter(breakStart)) {
            throw new BusinessException(
                    "점심 종료시간은 시작시간보다 늦어야 합니다."
            );
        }

        if (breakStart.isBefore(workStart)
                || breakEnd.isAfter(workEnd)) {
            throw new BusinessException(
                    "점심 휴게시간이 근무시간 밖에 있습니다."
            );
        }

        long calculatedMinutes = Duration.between(
                breakStart,
                breakEnd
        ).toMinutes();

        if (calculatedMinutes <= 0 || calculatedMinutes > 480) {
            throw new BusinessException(
                    "점심 휴게시간이 유효하지 않습니다."
            );
        }

        if (breakMinutes == null
                || breakMinutes.longValue() != calculatedMinutes) {
            throw new BusinessException(
                    "점심 구간과 휴게 분 수가 일치하지 않습니다."
            );
        }
    }
}
