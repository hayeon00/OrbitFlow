
package com.finalproj.orbitflow.attendance.rule.controller;

import com.finalproj.orbitflow.attendance.rule.dto.request.DefaultRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleCreateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.response.DefaultRuleResDto;
import com.finalproj.orbitflow.attendance.rule.dto.response.EmployeeRuleResDto;
import com.finalproj.orbitflow.attendance.rule.service.AttendanceRuleService;

import com.finalproj.orbitflow.attendance.leave.dto.request.LeaveTimePolicyUpdateReqDto;
import com.finalproj.orbitflow.attendance.leave.dto.response.LeaveTimePolicyResDto;
import com.finalproj.orbitflow.attendance.leave.entity.LeaveTimePolicy;
import com.finalproj.orbitflow.attendance.leave.service.LeaveTimePolicyService;

import com.finalproj.orbitflow.global.common.ResponseDto;
import com.finalproj.orbitflow.global.security.SecurityUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class AttendanceRuleController {

    private final AttendanceRuleService attendanceRuleService;
    private final LeaveTimePolicyService leaveTimePolicyService;

    // ==========================================
    // 1. 회사 기본 근태 규칙 조회
    // ==========================================

    @GetMapping("/default")
    public ResponseEntity<ResponseDto<DefaultRuleResDto>> getDefaultRule(
            @AuthenticationPrincipal SecurityUser admin
    ) {
        DefaultRuleResDto result =
                attendanceRuleService.getDefaultRule(
                        admin.getCompanyId()
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "기본 규칙 조회 성공",
                        result
                )
        );
    }

    // ==========================================
    // 2. 회사 기본 근태 규칙 수정
    // 출퇴근시간 + 점심 휴게시간
    // ==========================================

    @PutMapping("/default")
    public ResponseEntity<ResponseDto<DefaultRuleResDto>> updateDefaultRule(
            @AuthenticationPrincipal SecurityUser admin,
            @Valid @RequestBody DefaultRuleUpdateReqDto request
    ) {
        DefaultRuleResDto result =
                attendanceRuleService.updateDefaultRule(
                        admin.getCompanyId(),
                        request
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "회사 기본 규칙이 성공적으로 수정되었습니다.",
                        result
                )
        );
    }

    // ==========================================
    // 3. 사원별 예외 규칙 목록 조회
    // ==========================================

    @GetMapping("/exception")
    public ResponseEntity<ResponseDto<List<EmployeeRuleResDto>>> getExceptionRules(
            @AuthenticationPrincipal SecurityUser admin
    ) {
        List<EmployeeRuleResDto> result =
                attendanceRuleService.getExceptionRules(
                        admin.getCompanyId()
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "예외 규칙 목록 조회 성공",
                        result
                )
        );
    }

    // ==========================================
    // 4. 사원별 예외 규칙 상세 조회
    // ==========================================

    @GetMapping("/exception/{ruleId}")
    public ResponseEntity<ResponseDto<EmployeeRuleResDto>> getExceptionRuleDetail(
            @AuthenticationPrincipal SecurityUser admin,
            @PathVariable Long ruleId
    ) {
        EmployeeRuleResDto result =
                attendanceRuleService.getExceptionRuleDetail(
                        admin.getCompanyId(),
                        ruleId
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "예외 규칙 상세 조회 성공",
                        result
                )
        );
    }

    // ==========================================
    // 5. 사원별 예외 규칙 생성
    // ==========================================

    @PostMapping("/exception")
    public ResponseEntity<ResponseDto<EmployeeRuleResDto>> createExceptionRule(
            @AuthenticationPrincipal SecurityUser admin,
            @Valid @RequestBody EmpAttRuleCreateReqDto request
    ) {
        EmployeeRuleResDto result =
                attendanceRuleService.createExceptionRule(
                        admin.getCompanyId(),
                        request
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "사원별 예외 규칙이 성공적으로 등록되었습니다.",
                        result
                )
        );
    }

    // ==========================================
    // 6. 사원별 예외 규칙 수정
    // ==========================================

    @PutMapping("/exception/{ruleId}")
    public ResponseEntity<ResponseDto<EmployeeRuleResDto>> updateExceptionRule(
            @AuthenticationPrincipal SecurityUser admin,
            @PathVariable Long ruleId,
            @Valid @RequestBody EmpAttRuleUpdateReqDto request
    ) {
        EmployeeRuleResDto result =
                attendanceRuleService.updateExceptionRule(
                        admin.getCompanyId(),
                        ruleId,
                        request
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "사원별 예외 규칙이 성공적으로 수정되었습니다.",
                        result
                )
        );
    }

    // ==========================================
    // 7. 사원별 예외 규칙 삭제
    // ==========================================

    @DeleteMapping("/exception/{ruleId}")
    public ResponseEntity<ResponseDto<Void>> deleteExceptionRule(
            @AuthenticationPrincipal SecurityUser admin,
            @PathVariable Long ruleId
    ) {
        attendanceRuleService.deleteExceptionRule(
                admin.getCompanyId(),
                ruleId
        );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "해당 예외 규칙이 삭제되었습니다.",
                        null
                )
        );
    }

    // ==========================================
    // 8. 회사별 반차 시간 정책 조회
    // ==========================================

    @GetMapping("/leave-time-policy")
    public ResponseEntity<ResponseDto<LeaveTimePolicyResDto>> getLeaveTimePolicy(
            @AuthenticationPrincipal SecurityUser admin
    ) {
        LeaveTimePolicy policy =
                leaveTimePolicyService.getPolicy(
                        admin.getCompanyId()
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "회사별 반차 시간 정책 조회 성공",
                        new LeaveTimePolicyResDto(policy)
                )
        );
    }

    // ==========================================
    // 9. 회사별 반차 시간 정책 수정
    // ==========================================

    @PutMapping("/leave-time-policy")
    public ResponseEntity<ResponseDto<LeaveTimePolicyResDto>> updateLeaveTimePolicy(
            @AuthenticationPrincipal SecurityUser admin,
            @Valid @RequestBody LeaveTimePolicyUpdateReqDto request
    ) {
        LeaveTimePolicy policy =
                leaveTimePolicyService.savePolicy(
                        admin.getCompanyId(),
                        request.morningHalfReturnTime(),
                        request.afternoonHalfLeaveTime()
                );

        return ResponseEntity.ok(
                ResponseDto.ok(
                        "회사별 반차 시간 정책이 성공적으로 저장되었습니다.",
                        new LeaveTimePolicyResDto(policy)
                )
        );
    }
}
