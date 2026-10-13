package com.finalproj.orbitflow.attendance.rule.controller;

import com.finalproj.orbitflow.attendance.rule.dto.response.DefaultRuleResDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.DefaultRuleUpdateReqDto;
import com.finalproj.orbitflow.attendance.rule.service.AttendanceRuleService;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleCreateReqDto;
import com.finalproj.orbitflow.attendance.rule.dto.response.EmployeeRuleResDto;
import com.finalproj.orbitflow.attendance.rule.dto.request.EmpAttRuleUpdateReqDto;
import com.finalproj.orbitflow.global.common.ResponseDto;
import com.finalproj.orbitflow.global.security.SecurityUser;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Please explain the class!!!
 *
 * @author : rlagkdus
 * @filename : AttendanceRuleController
 * @since : 2025. 12. 22. 월요일
 */


@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class AttendanceRuleController {

    private final AttendanceRuleService attendanceRuleService;

    @GetMapping("/default")
    public ResponseEntity<ResponseDto<DefaultRuleResDto>> getDefaultRule(@AuthenticationPrincipal SecurityUser admin) {
        DefaultRuleResDto result = attendanceRuleService.getDefaultRule(admin.getCompanyId());
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "기본 규칙 조회 성공",
                        result
                ));
    }

    @PutMapping("/default")
    public ResponseEntity<ResponseDto<DefaultRuleResDto>> updateDefaultRule(
            @AuthenticationPrincipal SecurityUser admin,
            @Valid @RequestBody DefaultRuleUpdateReqDto request
    ) {
        DefaultRuleResDto result = attendanceRuleService.updateDefaultRule(admin.getCompanyId(), request);
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "회사 기본 규칙이 성공적으로 수정되었습니다.",
                        result
                )
        );
    }

    @GetMapping("/exception")
    public ResponseEntity<ResponseDto<List<EmployeeRuleResDto>>> getExceptionRules(@AuthenticationPrincipal SecurityUser admin) {
        List<EmployeeRuleResDto> result = attendanceRuleService.getExceptionRules(admin.getCompanyId());
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "예외 규칙 목록 조회 성공",
                        result
                ));
    }

    @GetMapping("/exception/{ruleId}")
    public ResponseEntity<ResponseDto<EmployeeRuleResDto>> getExceptionRuleDetail(
            @AuthenticationPrincipal SecurityUser admin,
            @PathVariable Long ruleId) {
        EmployeeRuleResDto result = attendanceRuleService.getExceptionRuleDetail(admin.getCompanyId(), ruleId);
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "예외 규칙 상세 조회 성공",
                        result
                ));
    }

    @PostMapping("/exception")
    public ResponseEntity<ResponseDto<EmployeeRuleResDto>> createExceptionRule(
            @AuthenticationPrincipal SecurityUser admin,
            @RequestBody EmpAttRuleCreateReqDto request) {
        EmployeeRuleResDto result = attendanceRuleService.createExceptionRule(admin.getCompanyId(), request);
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "사원별 예외 규칙이 성공적으로 등록되었습니다.",
                        result
                ));
    }

    @PutMapping("/exception/{ruleId}")
    public ResponseEntity<ResponseDto<EmployeeRuleResDto>> updateExceptionRule(
            @AuthenticationPrincipal SecurityUser admin,
            @PathVariable Long ruleId,
            @RequestBody EmpAttRuleUpdateReqDto request) {
        EmployeeRuleResDto result = attendanceRuleService.updateExceptionRule(admin.getCompanyId(), ruleId, request);
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "사원별 예외 규칙이 성공적으로 수정되었습니다.",
                        result
                ));
    }

    @DeleteMapping("/exception/{ruleId}")
    public ResponseEntity<ResponseDto<Void>> deleteExceptionRule(
            @AuthenticationPrincipal SecurityUser admin,
            @PathVariable Long ruleId) {
        attendanceRuleService.deleteExceptionRule(admin.getCompanyId(), ruleId);
        return ResponseEntity.ok(
                ResponseDto.ok(
                        "해당 예외 규칙이 삭제되었습니다.",
                        null
                ));
    }

}