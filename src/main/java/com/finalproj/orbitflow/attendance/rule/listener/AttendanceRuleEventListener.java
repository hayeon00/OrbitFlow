package com.finalproj.orbitflow.attendance.rule.listener;

import com.finalproj.orbitflow.attendance.rule.service.AttendanceRuleService;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.event.CompanyCreatedEvent;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 회사 생성 트랜잭션이 커밋된 후 기본 근태 규칙을 비동기로 초기화한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttendanceRuleEventListener {

    private final CompanyRepository companyRepository;
    private final AttendanceRuleService attendanceRuleService;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCompanyCreated(CompanyCreatedEvent event) {
        Long companyId = event.getCompanyId();

        try {
            Company company = companyRepository.findById(companyId)
                    .orElse(null);

            if (company == null) {
                log.warn(
                        "기본 근태 규칙 초기화 대상 회사를 찾을 수 없습니다. companyId={}",
                        companyId
                );
                return;
            }

            attendanceRuleService.createDefaultAttendanceRule(company);

            log.info(
                    "기본 근태 규칙 초기화 처리 완료. companyId={}",
                    companyId
            );

        } catch (Exception e) {
            log.error(
                    "기본 근태 규칙 초기화 실패. companyId={}",
                    companyId,
                    e
            );
        }
    }
}