package com.openplan.backend.weeklyplan.dto;

import java.util.List;

/**
 * 주간 화면 조립 응답 (정본 openapi.yaml {@code WeeklyPlanView}) — GET {@code /weekly-plans}.
 *
 * <p>계획은 {@code plan} 한 겹 아래 둔다(FE는 {@code data.plan}으로 읽는다). <b>없는 주차는 오류가 아니다</b> —
 * 200 + {@code data.plan = null}("이 주는 아직 계획 없음"을 정상 상태로 표현). 캘린더 렌더링용 블록은
 * {@code blocks}(start_at 순).
 *
 * <p>{@code fixedSchedules}(이슈 #90)는 그 주에 걸치는 고정 일정 전량 + {@code activeThisWeek} —
 * <b>계획 존재와 무관</b>하다(고정 일정은 계획이 없는 주에도 화면에 표시돼야 한다). 그래서
 * {@link #empty()}도 {@code fixedSchedules}를 받는다 — plan=null인 주에도 고정 일정은 비어 있지
 * 않을 수 있다.
 *
 * <p>정본의 {@code availability·summary·validationSummary}는 타 도메인 의존으로 후속(④) 편입 —
 * 지금은 {@code plan}·{@code blocks}·{@code fixedSchedules} 세 겹만 계약 고정.
 */
public record WeeklyPlanView(
        WeeklyPlanResponse plan,
        List<PlanBlockResponse> blocks,
        List<FixedScheduleWeekView> fixedSchedules) {

    /** 계획 존재 — plan 요약 + 블록 목록 + 그 주 고정 일정. placedBlockCount는 블록 수로 파생. */
    public static WeeklyPlanView of(WeeklyPlanResponse plan, List<PlanBlockResponse> blocks,
                                    List<FixedScheduleWeekView> fixedSchedules) {
        return new WeeklyPlanView(plan, blocks, fixedSchedules);
    }

    /**
     * 없는 주차 — plan=null(명시적으로 직렬화), blocks=빈 목록. {@code fixedSchedules}는 계획 존재와
     * 무관하므로 호출자가 그 주 조회 결과를 그대로 넘긴다(빈 목록으로 고정하지 않음).
     */
    public static WeeklyPlanView empty(List<FixedScheduleWeekView> fixedSchedules) {
        return new WeeklyPlanView(null, List.of(), fixedSchedules);
    }
}
