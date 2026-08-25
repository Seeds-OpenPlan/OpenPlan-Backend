package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.PlanBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 계획 블록 저장소 (ST-B2-08). 소유는 소속 weekly_plan(=user_id 스코프)으로 판정 — 서비스가 계획을 선판정한다.
 */
public interface PlanBlockRepository extends JpaRepository<PlanBlock, UUID> {

    /**
     * 주간계획의 블록 목록 뷰 — 캘린더 렌더링용. tasks/schedules 조인으로 title·projectId를 파생한다(정본 PlanBlock shape).
     * 정렬 start_at 순(동시각은 created_at 순으로 결정적). 별칭은 큰따옴표로 감싸 프로젝션 프로퍼티명과 정확히 일치시킨다.
     */
    @Query(value = """
            SELECT pb.plan_block_id   AS "planBlockId",
                   pb.weekly_plan_id  AS "weeklyPlanId",
                   pb.block_type      AS "blockType",
                   pb.task_id         AS "taskId",
                   pb.schedule_id     AS "scheduleId",
                   COALESCE(t.title, s.title) AS "title",
                   t.project_id       AS "projectId",
                   pb.start_at        AS "startAt",
                   pb.end_at          AS "endAt",
                   pb.status          AS "status"
            FROM plan_blocks pb
            LEFT JOIN tasks t     ON t.task_id = pb.task_id
            LEFT JOIN schedules s ON s.schedule_id = pb.schedule_id
            WHERE pb.weekly_plan_id = :planId
            ORDER BY pb.start_at ASC, pb.created_at ASC
            """, nativeQuery = true)
    List<PlanBlockView> findViewsByWeeklyPlanId(@Param("planId") UUID planId);

    /** 검증 스냅샷 조립용(ST-B2-09) — 그 주 블록 엔티티. BlockView 매핑에 start/end·type·taskId만 필요. */
    List<PlanBlock> findByWeeklyPlanId(UUID weeklyPlanId);
}
