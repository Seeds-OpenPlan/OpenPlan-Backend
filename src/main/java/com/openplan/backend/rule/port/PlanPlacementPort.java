package com.openplan.backend.rule.port;

import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;

import java.util.List;
import java.util.UUID;

/**
 * SS-05 자동 배치 제안 (RB-PLAN-01). 소비자는 구현이 아니라 이 계약에만 의존한다({@link PlanValidationPort}와 동일 관례).
 *
 * <p>{@code taskIds}의 태스크 사실은 {@code snapshot.taskFacts()}에 들어 있어야 한다(라우트가 후보 태스크를
 * 스냅샷에 포함시켜 넘긴다). NFR-029: ≤ 5초.
 *
 * <p>🔴 <b>결정성은 이 포트의 불변식이 아니라 구현별 성질이다.</b> 규칙 구현
 * ({@code FirstFitPlacementEngine})은 동일 snapshot·taskIds → 동일 result 지만,
 * AI 구현({@code AiPlacementAdapter})은 같은 스냅샷에도 다른 초안을 낸다. <b>이 결과를 캐싱하거나
 * 재시도 안전성의 근거로 삼지 말 것.</b> charter P1(결정성·LLM 호출 금지)의 대상은 <b>판정</b>하는
 * {@link PlanValidationPort} 이고, 이 포트는 <b>생성</b>하는 자리다 — 만드는 건 AI, 판정은 규칙.
 */
public interface PlanPlacementPort {

    PlacementResult propose(PlanSnapshot snapshot, List<UUID> taskIds);
}
