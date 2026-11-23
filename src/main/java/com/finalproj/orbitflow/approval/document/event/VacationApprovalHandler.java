
package com.finalproj.orbitflow.approval.document.event;

import com.finalproj.orbitflow.approval.document.service.application.ApprovalEventTxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 휴가 승인 처리는
 * DocumentApprovalApplicationService의
 * 최종 승인 트랜잭션에서 직접 실행한다.
 *
 * 기존 비동기 이벤트 리스너는 사용하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class VacationApprovalHandler {

    private final ApprovalEventTxService approvalEventTxService;

    // 기존 @Async + AFTER_COMMIT 휴가 처리 제거.
    // 중복 차감 방지를 위해 이벤트를 구독하지 않는다.
}
