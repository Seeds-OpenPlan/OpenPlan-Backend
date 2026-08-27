package com.openplan.backend.rule.model;

import java.util.List;
import java.util.UUID;

/**
 * 자동 배치 결과 (SS-05 / RB-PLAN-01) — 배치 제안기의 출력.
 *
 * <p>{@code placements} = 빈 슬롯에 배치된 제안(입력 정렬 순), {@code unplacedTaskIds} = 가용 슬롯 부족으로
 * 배치 못 한 태스크(예상시간 없음 포함).
 *
 * <p><b>{@code reason} 은 제안기가 채울 수도, 비울 수도 있다.</b> 규칙 엔진(first-fit)은 정렬 규칙이
 * 고정이라 문구도 고정이므로 비워 두고 라우트가 카탈로그 문구를 넣는다(P4 정합). 반면 AI 초안은
 * 매번 근거가 달라 <b>제안기만이 그 문구를 안다</b> — 그때는 여기에 담아 올린다(AI 계약 §4:
 * "reason 은 필수다 … AI 제안도 근거를 동반한다"). 비어 있으면 라우트가 기존 문구로 대체한다.
 *
 * <p>🔴 <b>결정성(P1)은 규칙 구현에만 걸린다.</b> {@link com.openplan.backend.rule.port.PlanPlacementPort}
 * 의 "같은 입력 → 같은 출력"은 first-fit 의 성질이지 포트 전체의 불변식이 아니다 — AI 구현은 같은
 * 스냅샷에도 다른 초안을 낸다. 판정(검증 엔진)은 여전히 결정적이며, 그쪽이 charter P1 의 대상이다.
 */
public record PlacementResult(List<ProposedPlacement> placements, List<UUID> unplacedTaskIds, String reason) {

    /** 규칙 엔진용 — 문구는 라우트가 채운다. */
    public PlacementResult(List<ProposedPlacement> placements, List<UUID> unplacedTaskIds) {
        this(placements, unplacedTaskIds, null);
    }

    /** 제안기가 직접 문구를 실어 보냈는가(AI 경로). */
    public boolean hasReason() {
        return reason != null && !reason.isBlank();
    }
}
