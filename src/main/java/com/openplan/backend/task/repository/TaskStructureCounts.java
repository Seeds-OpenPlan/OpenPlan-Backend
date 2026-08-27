package com.openplan.backend.task.repository;

/**
 * 구조 부족 경고 판정 입력 (RB-PROJ-02) — 한 프로젝트의 태스크 집계 3종을 <b>한 쿼리로</b> 담는 read-model
 * (code-structure §4 프로젝션 관례 — repository 패키지 소유).
 *
 * <p><b>세 값을 따로 세면 안 되는 이유</b>: 판정 문구가 세 숫자를 함께 쓴다. 조회 사이에 태스크가
 * 생기거나 지워지면 "태스크가 2건입니다"와 "예상시간이 비어 있는 미완료 태스크가 3건 있습니다"가
 * 한 응답에 같이 담기는 자기모순이 난다 — 정본이 약속한 결정성(P1)도 그만큼 무너진다.
 * 한 스냅샷에서 세면 그 창이 사라진다.
 *
 * @param total   태스크 총수 — <b>상태 무관</b>(구조는 완료 여부와 독립된 프로젝트 속성)
 * @param remaining 미완료(status ≠ COMPLETED) 수
 * @param missingEstimates 미완료 중 estimatedMinutes가 null인 수
 */
public record TaskStructureCounts(long total, long remaining, long missingEstimates) {
}
