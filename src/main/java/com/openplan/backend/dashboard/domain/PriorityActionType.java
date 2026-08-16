package com.openplan.backend.dashboard.domain;

/**
 * RB-DASH-01 최상위 1행동 유형 — openapi {@code priorityAction.actionType} enum과 1:1(6종).
 *
 * <p><b>선언 순서 = 전순서(위가 우선)</b> — us-decisions-kr.md §3.2(Q3 확정문) 그대로.
 * 확정문의 7유형 중 <b>OVERLAP_CONFLICT(V1, 원래 2위)는 여기 없다</b> — openapi enum 자체에 대응 값이
 * 없기 때문이다(나머지 6종은 이름·순서까지 확정문과 정확히 대응). 정본 갱신 누락인지, V1을 이번 계약
 * 버전에서 의도적으로 제외한 것인지 확인되지 않았다 — stats-dashboard-notes.md §2.1, 리드 확인 필요.
 */
public enum PriorityActionType {
    RESOLVE_FIXED_CONFLICT,   // 1위 — V2, openIssues FIXED_CONFLICT ≥ 1
    PLACE_UNASSIGNED,         // 3위 — unassignedCount ≥ 1
    HANDLE_DEADLINE,          // 4위 — 마감 임박(D-3) 미완료 태스크 ≥ 1
    REPLACE_TODAY_INCOMPLETE, // 5위 — 오늘 종료된 미완료 TASK 블록 ≥ 1
    RESOLVE_CAPACITY,         // 6위 — V3, openIssues CAPACITY_EXCEEDED ≥ 1
    FIX_OUT_OF_WBS            // 7위(최하위) — V5, openIssues OUT_OF_WBS ≥ 1
}
