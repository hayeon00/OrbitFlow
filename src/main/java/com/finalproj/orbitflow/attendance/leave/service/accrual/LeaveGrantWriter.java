
package com.finalproj.orbitflow.attendance.leave.service.accrual;

import com.finalproj.orbitflow.attendance.leave.entity.LeaveGrant;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveBalanceRepository;
import com.finalproj.orbitflow.attendance.leave.repository.LeaveGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 연차 발생 저장 (월별·정기 공통).
 *
 * 발생 이력(grant_history)과 연도별 부여량(leave_balance)을
 * 호출자의 트랜잭션 안에서 함께 저장한다.
 */
@Component
@RequiredArgsConstructor
public class LeaveGrantWriter {

    private final LeaveGrantRepository leaveGrantRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;

    /**
     * 발생 이력은 UNIQUE 제약조건(uk_leave_grant_event)으로 중복을 막고,
     * 연도별 부여량은 MySQL 원자적 증가 연산으로 갱신한다.
     *
     * 이력 저장이 중복으로 실패하면 DataIntegrityViolationException이
     * 발생하고, 호출자 트랜잭션 전체가 롤백된다.
     *
     * 주의: 부여량 증가 쿼리가 영속성 컨텍스트를 비우므로(clearAutomatically)
     * 호출 이후 기존에 조회한 엔티티는 준영속 상태가 된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grant(
            Long companyId,
            Long employeeId,
            LocalDate grantDate,
            BigDecimal days,
            String grantType,
            LocalDate expirationDate
    ) {

        // 1. 실제 연차 발생 이력 저장
        LeaveGrant grant = LeaveGrant.issue(
                companyId,
                employeeId,
                grantDate,
                days,
                grantType,
                expirationDate
        );

        // UNIQUE 제약조건 위반을 이 시점에서 확인한다.
        leaveGrantRepository.saveAndFlush(grant);

        // 2. 발생 이력 저장에 성공한 경우에만
        //    연도별 부여량과 잔여량을 원자적으로 증가시킨다.
        leaveBalanceRepository.addGrantedDaysAtomically(
                companyId,
                employeeId,
                grantDate.getYear(),
                days
        );
    }
}
