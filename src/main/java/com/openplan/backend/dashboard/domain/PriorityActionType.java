package com.openplan.backend.dashboard.domain;

/**
 * RB-DASH-01 최상위 1행동 유형 — openapi {@code priorityAction.actionType} enum과 1:1(6종).
 *
 * <p><b>선언 순서 = 전순서(위가 우선)</b> — us-decisions-kr.md §3.2(Q3 확정문) 그대로 7종 전부.
 *
 * <p>확정문은 상태형({@code FIXED_CONFLICT}), openapi enum은 행동형({@code RESOLVE_FIXED_CONFLICT})으로
 * 표기가 다르다. 이 개명에서 <b>2위 {@code OVERLAP_CONFLICT}만 누락</b>돼 있었고(나머지 6종은 규칙적으로
 * 대응), 그 탓에 겹침(V1)만 있는 계획이 사다리를 그대로 흘러내려 <b>차단 이슈를 안고도 긍정 상태로
 * 보이는</b> 결함이 있었다. 확정문의 행동 문구 "일정 겹침 해소"를 1위와 같은 방식으로 옮겨
 * {@code RESOLVE_OVERLAP}으로 신설했다(사용자 확정 2026-08-18 · ADR-0013 양쪽 동시 반영).
 */
public enum PriorityActionType {
    RESOLVE_FIXED_CONFLICT,   // 1위 — V2, openIssues FIXED_CONFLICT ≥ 1
    RESOLVE_OVERLAP,          // 2위 — V1, openIssues OVERLAP ≥ 1 (차단류이므로 경고류 앞)
    PLACE_UNASSIGNED,         // 3위 — unassignedCount ≥ 1
    HANDLE_DEADLINE,          // 4위 — 마감 임박(D-3) 미완료 태스크 ≥ 1
    REPLACE_TODAY_INCOMPLETE, // 5위 — 오늘 종료된 미완료 TASK 블록 ≥ 1
    RESOLVE_CAPACITY,         // 6위 — V3, openIssues CAPACITY_EXCEEDED ≥ 1
    FIX_OUT_OF_WBS            // 7위(최하위) — V5, openIssues OUT_OF_WBS ≥ 1
}
