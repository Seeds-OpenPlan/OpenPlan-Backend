package com.openplan.backend.executionlog.service.port;

import java.util.UUID;

/**
 * 계획 블록 소유 확인 경계 (PLAN-15). {@code plan_blocks}는 ST-B2-08 소유라 서비스·리포지토리가
 * 직접 조인·SQL하지 않고 port를 경유한다(task 패키지의 {@code PlanBlockStatusMirror}와 같은 방식).
 *
 * <p>존재만으로는 부족하다 — FK는 "그 블록이 있다"만 보장하고 "내 것이다"는 보장하지 않는다.
 * 확인하지 않으면 남의 블록 id를 실어 내 이력에 붙일 수 있다.
 */
public interface PlanBlockOwnershipChecker {

    /** 해당 블록이 존재하고 그 소유자가 {@code userId}인가. 부재·타인 → false. */
    boolean isOwnedBy(UUID planBlockId, UUID userId);
}
