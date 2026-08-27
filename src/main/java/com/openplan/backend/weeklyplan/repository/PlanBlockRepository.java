package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.PlanBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * 소유자 스코프 단건(blockId) — 삭제·이동 공용(PLAN-16·18·19·20). {@code plan_blocks}에 user_id가 없어
     * 소속 {@code weekly_plans.user_id}로 소유를 판정한다. 부재·타인 → empty → 404 E-COM-004(존재 은닉).
     */
    @Query("""
            SELECT pb FROM PlanBlock pb
             WHERE pb.id = :blockId
               AND pb.weeklyPlanId IN (SELECT wp.id FROM WeeklyPlan wp WHERE wp.userId = :userId)
            """)
    Optional<PlanBlock> findByIdAndUserId(@Param("blockId") UUID blockId, @Param("userId") UUID userId);

    /**
     * 같은 태스크의 <b>다른</b> 블록이 남아 있는지(TASK 블록 삭제 후 UNASSIGNED 복귀 판정 — PLAN-16 / TT-2).
     * 삭제 대상 자신을 제외해야 하므로 {@code excludeBlockId}로 뺀다.
     */
    boolean existsByTaskIdAndIdNot(UUID taskId, UUID excludeBlockId);

    /** 단건 뷰(blockId) — 이동 응답용. {@link #findViewsByWeeklyPlanId}와 동일 조인·별칭. */
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
            WHERE pb.plan_block_id = :blockId
            """, nativeQuery = true)
    Optional<PlanBlockView> findViewByBlockId(@Param("blockId") UUID blockId);

    /**
     * 블록 이동·시간 조정 (PLAN-19·20) — 시각과 소속 주간계획을 한 번에 바꾼다. {@code weekly_plan_id}는
     * 엔티티에서 {@code updatable=false}(생성 후 불변 의도)라 dirty update로는 못 바꾸므로, 이동은 이 벌크
     * UPDATE로 우회한다(같은 주 이동이면 {@code planId}가 기존과 동일). {@code clearAutomatically}로 영속
     * 컨텍스트를 비워 이후 뷰 재조회가 갱신된 값을 본다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PlanBlock pb
               SET pb.startAt = :startAt, pb.endAt = :endAt, pb.weeklyPlanId = :planId
             WHERE pb.id = :blockId
            """)
    void reschedule(@Param("blockId") UUID blockId, @Param("startAt") Instant startAt,
                    @Param("endAt") Instant endAt, @Param("planId") UUID planId);
}
