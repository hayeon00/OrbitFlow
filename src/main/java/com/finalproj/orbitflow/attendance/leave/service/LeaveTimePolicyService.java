
package com.finalproj.orbitflow.attendance.leave.service;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveTimePolicyRepository;
import com.finalproj.orbitflow.attendance.rule.entity.AttendanceRule;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class LeaveTimePolicyService {

    private final LeaveTimePolicyRepository policyRepository;
    private final AttendanceRuleRepository attendanceRuleRepository;

    @Transactional
    public LeaveTimePolicy savePolicy(
            Long companyId,
            LocalTime morningReturnTime,
            LocalTime afternoonLeaveTime
    ) {
        AttendanceRule rule = attendanceRuleRepository
                .findByCompanyIdAndIsDefaultTrue(companyId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "회사 기본 근무 규칙이 없습니다."
                        )
                );

        validateTime(
                morningReturnTime,
                rule,
                "오전반차 출근시간"
        );

        validateTime(
                afternoonLeaveTime,
                rule,
                "오후반차 퇴근시간"
        );

        LeaveTimePolicy policy = policyRepository
                .findByCompanyId(companyId)
                .orElse(null);

        if (policy == null) {
            policy = LeaveTimePolicy.create(
                    companyId,
                    morningReturnTime,
                    afternoonLeaveTime
            );
        } else {
            policy.changeTimes(
                    morningReturnTime,
                    afternoonLeaveTime
            );
        }

        return policyRepository.save(policy);
    }

    @Transactional(readOnly = true)
    public LeaveTimePolicy getPolicy(Long companyId) {
        return policyRepository.findByCompanyId(companyId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "회사별 반차 시간 정책이 없습니다."
                        )
                );
    }

    private void validateTime(
            LocalTime time,
            AttendanceRule rule,
            String fieldName
    ) {
        if (time == null) {
            throw new IllegalArgumentException(
                    fieldName + "은 필수입니다."
            );
        }

        if (!time.isAfter(rule.getDefaultStartTime())
                || !time.isBefore(rule.getDefaultEndTime())) {
            throw new IllegalArgumentException(
                    fieldName
                            + "은 회사 근무시간 안에 있어야 합니다."
            );
        }

        LocalTime breakStart = rule.getBreakStartTime();
        LocalTime breakEnd = rule.getBreakEndTime();

        if (breakStart != null && breakEnd != null
                && time.isAfter(breakStart)
                && time.isBefore(breakEnd)) {
            throw new IllegalArgumentException(
                    fieldName
                            + "을 점심 휴게시간 내부로 설정할 수 없습니다."
            );
        }
    }
}
