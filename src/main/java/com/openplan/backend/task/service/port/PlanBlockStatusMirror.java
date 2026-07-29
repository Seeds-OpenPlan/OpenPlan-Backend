package com.openplan.backend.task.service.port;

import com.openplan.backend.task.domain.TaskStatus;

import java.util.UUID;

/**
 * 태스크 완료 전환 시 plan_blocks 상태 미러 (TB-1 · D-2). ST-B2-08(주간계획 블록) 이관 계약 — 인터페이스 불변.
 *
 * <p>완료 토글(EP-5)에서 태스크 status 전환과 <b>동일 tx</b>로 블록 status를 동기화한다(SSM §3 매핑).
 * plan_blocks를 서비스/리포지토리가 직접 조인·SQL하지 않도록 경계를 port로 물리화한다(SSM §2).
 */
public interface PlanBlockStatusMirror {

    /**
     * 해당 태스크의 전 블록 status를 SSM §3 매핑으로 영속: COMPLETED→'COMPLETED', IN_PROGRESS→'SCHEDULED'.
     * UNASSIGNED 전달 금지(블록 0 상태라 매핑 무의미 — IllegalArgumentException).
     * 계약: 호출자 tx 참여(REQUIRED — 동일 tx) · 멱등(절대값 SET) · flush 전제 없음(tasks 미독취).
     */
    void mirrorStatus(UUID taskId, TaskStatus taskStatus);

    /** 미완료 되돌리기 착지 판정 입력(TT-4 vs TT-5) — plan_blocks 직접 조인 금지 경계의 유일한 조회 계약(SSM §2). */
    boolean hasBlocks(UUID taskId);
}
