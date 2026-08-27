package com.openplan.backend.stats.dto;

/**
 * 예상 시간 보정 제안 (SS-11 / RB-STAT-02 — 정본 openapi.yaml {@code CorrectionProposal} 1:1).
 *
 * <p><b>제안일 뿐 자동 적용이 없다</b>(C-2/P2) — 사용자가 {@code basis}와 {@code sampleSize}를 보고
 * 채택을 결정한다. 그 판단 재료가 응답에 함께 실리는 것이 이 shape의 설계 의도이므로 필드를 빼지 말 것.
 *
 * @param proposedEstimatedMinutes 제안값(분) — 5의 배수, 하한 5
 * @param basis 산출 근거(P4 사실 서술) — 예: {@code "해당 카테고리 편차율 +20% 반영"}
 * @param sampleSize 집계에 실제로 쓰인 <b>수행 이력(로그) 건수</b> — 태스크 건수가 아니다.
 *                   단일 태스크를 여러 번 기록해도 그만큼 늘어난다(FE 표시 문구는 "수행 이력 N건" 계열로).
 *                   예상시간이 없는 태스크의 로그는 편차 계산에서 제외되므로 이 수에도 포함되지 않는다 —
 *                   근거로 제시하는 숫자가 계산에 쓰인 표본과 어긋나면 사용자가 검산할 수 없다.
 */
public record CorrectionProposalResponse(
        int proposedEstimatedMinutes,
        String basis,
        int sampleSize) {
}
