package com.openplan.backend.rule.model;

import java.util.List;
import java.util.UUID;

/**
 * 자동 배치 결과 (SS-05 / RB-PLAN-01) — 엔진의 순수 출력.
 *
 * <p>{@code placements} = 빈 슬롯에 배치된 제안(입력 정렬 순), {@code unplacedTaskIds} = 가용 슬롯 부족으로
 * 배치 못 한 태스크(예상시간 없음 포함). 같은 입력 → 같은 출력(P1). {@code reason}(정렬 규칙 설명)은
 * 라우트가 응답에 채운다 — 사용자 노출 문구라 순수 엔진 밖 관심사(P4 카탈로그 정합).
 */
public record PlacementResult(List<ProposedPlacement> placements, List<UUID> unplacedTaskIds) {
}
