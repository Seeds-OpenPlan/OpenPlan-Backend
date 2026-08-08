package com.openplan.backend.weeklyplan.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 블록 목록 조회 프로젝션 (GET /weekly-plans). 정본 openapi.yaml {@code PlanBlock} shape —
 * {@code title}(task/schedule 제목 조인 파생)·{@code projectId}(TASK만, SCHEDULE은 null)를 포함한다.
 * plan_blocks에는 title 컬럼이 없으므로 tasks/schedules 조인으로 파생한다(data-model §2.2).
 */
public interface PlanBlockView {

    UUID getPlanBlockId();

    UUID getWeeklyPlanId();

    String getBlockType();

    UUID getTaskId();

    UUID getScheduleId();

    String getTitle();

    UUID getProjectId();

    Instant getStartAt();

    Instant getEndAt();

    String getStatus();
}
