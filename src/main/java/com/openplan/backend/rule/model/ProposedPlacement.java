package com.openplan.backend.rule.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 자동 배치 제안 1건 (SS-05 / RB-PLAN-01) — 엔진의 순수 출력. <b>저장되지 않는다</b>(C-2 — 제안일 뿐).
 *
 * <p>rule 패키지 순수 타입이라 weeklyplan DTO({@code PlanBlockInput})에 의존하지 않는다 — 라우트가
 * 이 값을 응답 계약으로 매핑한다. 배치 대상은 항상 TASK다(자동 배치는 미배치 태스크만 대상).
 */
public record ProposedPlacement(UUID taskId, Instant startAt, Instant endAt) {
}
