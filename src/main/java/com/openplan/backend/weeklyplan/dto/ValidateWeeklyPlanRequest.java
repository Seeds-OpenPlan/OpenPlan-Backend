package com.openplan.backend.weeklyplan.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 검증 실행 요청 (ST-B2-09 / SS-06). 본문은 <b>선택</b>이다.
 *
 * <ul>
 *   <li>본문 없음 / {@code virtualBlocks} 없음 → 현재 저장된 초안 판정 + validation_issues 영속</li>
 *   <li>{@code virtualBlocks} 있음(빈 배열 포함) → <b>dry-run</b> — 무영속. 미저장 초안 미리보기(PLAN-11·FIX-07, C-4)</li>
 * </ul>
 *
 * <p>FE는 항상 {@code virtualBlocks} 키를 싣는다(빈 배열이라도) → 키 존재 = dry-run으로 판정한다.
 * {@code virtualTaskEdits}는 미저장 태스크 편집 반영(dueDate·estimatedMinutes) — dry-run에서만 의미.
 */
public record ValidateWeeklyPlanRequest(
        List<PlanBlockCreateRequest> virtualBlocks,
        List<VirtualTaskEdit> virtualTaskEdits) {

    /** 미저장 태스크 편집(dry-run) — V5·V6 판정 입력을 덮어쓴다. */
    public record VirtualTaskEdit(java.util.UUID taskId, Integer estimatedMinutes, LocalDate dueDate) {
    }
}
