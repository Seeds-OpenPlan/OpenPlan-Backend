package com.openplan.backend.rule.model;

/**
 * 재계획 전략 (SS-07~09 / RB-PLAN-03·04·05). {@code KEEP_CURRENT}(기준선)은 엔진이 생성하지 않는다 —
 * 현재 배치 그대로라 별도 로직·행이 없다(정본 replan_options.ck_replan_strategy에도 KEEP_CURRENT 없음).
 */
public enum ReplanStrategy {
    /** 최소 변경안 — 충돌(겹침) 블록만 인접 빈 슬롯으로 이동, 나머지 유지 (RB-PLAN-03). */
    MINIMAL_CHANGE,
    /** 마감 우선안 — 전 TASK를 마감일 순으로 앞 슬롯부터 재배치 (RB-PLAN-04). */
    DEADLINE_FIRST,
    /** 부하 분산안 — 요일별 초과분 큰 날의 TASK를 여유 있는 날로 분산 (RB-PLAN-05). */
    WORKLOAD_BALANCE
}
