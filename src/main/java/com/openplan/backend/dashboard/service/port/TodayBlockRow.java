package com.openplan.backend.dashboard.service.port;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code plan_blocks}(+ tasks/schedules 조인) 1행 — DASH-05/RB-DASH-02 오늘 실행 보드 원재료.
 *
 * @param taskId       TASK 블록만 값 존재(SCHEDULE 블록은 null — openapi todayBoard.items[].taskId와 동일 규약)
 * @param title        TASK면 tasks.title, SCHEDULE면 schedules.title (plan_blocks에 title 컬럼 없음)
 * @param estimatedMinutes TASK면 tasks.estimated_minutes, SCHEDULE면 schedules.estimated_minutes(둘 다 nullable)
 * @param completed    plan_blocks.status = COMPLETED
 * @param taskBlock    TODAY_INCOMPLETE(Q3 §3.2 5위) 판정용 — "TASK, status=SCHEDULED" 조건은 SCHEDULE 블록엔
 *                     적용되지 않는다(고정 일정은 "미완료"라는 개념이 없음)
 */
public record TodayBlockRow(
        UUID planBlockId, UUID taskId, String title,
        Instant startAt, Instant endAt, Integer estimatedMinutes,
        boolean completed, boolean taskBlock) {
}
