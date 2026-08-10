package com.openplan.backend.weeklyplan.domain;

/**
 * 주간 계획 상태 (ST-B2-07). V1 baseline {@code ck_weekly_plan_status} CHECK와 1:1 — 2값(ADR-0008).
 *
 * <p>DRAFT: 편집 가능한 초안(생성 시 기본). CONFIRMED: 확정(되돌리기 불가) — 전이는 ST-B2-09(확정 라우트) 소관.
 */
public enum WeeklyPlanStatus {
    DRAFT,
    CONFIRMED
}
