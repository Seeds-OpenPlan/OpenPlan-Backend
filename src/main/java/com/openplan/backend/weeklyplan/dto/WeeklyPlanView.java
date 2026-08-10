package com.openplan.backend.weeklyplan.dto;

import java.util.List;

/**
 * 주간 화면 조립 응답 (정본 openapi.yaml {@code WeeklyPlanView}) — GET {@code /weekly-plans}.
 *
 * <p>계획은 {@code plan} 한 겹 아래 둔다(FE는 {@code data.plan}으로 읽는다). <b>없는 주차는 오류가 아니다</b> —
 * 200 + {@code data.plan = null}("이 주는 아직 계획 없음"을 정상 상태로 표현). 캘린더 렌더링용 블록은
 * {@code blocks}(start_at 순).
 *
 * <p>정본의 {@code fixedSchedules·availability·summary·validationSummary}는 타 도메인 의존으로 후속(④) 편입 —
 * 지금은 {@code plan}·{@code blocks} 두 겹만 계약 고정.
 */
public record WeeklyPlanView(
        WeeklyPlanResponse plan,
        List<PlanBlockResponse> blocks) {

    /** 계획 존재 — plan 요약 + 블록 목록. placedBlockCount는 블록 수로 파생. */
    public static WeeklyPlanView of(WeeklyPlanResponse plan, List<PlanBlockResponse> blocks) {
        return new WeeklyPlanView(plan, blocks);
    }

    /** 없는 주차 — plan=null(명시적으로 직렬화), blocks=빈 목록. */
    public static WeeklyPlanView empty() {
        return new WeeklyPlanView(null, List.of());
    }
}
