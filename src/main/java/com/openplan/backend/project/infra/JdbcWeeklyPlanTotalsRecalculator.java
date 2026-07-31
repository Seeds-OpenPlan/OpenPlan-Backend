package com.openplan.backend.project.infra;

import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@link WeeklyPlanTotalsRecalculator} 잠정 구현 (B-4). PLAN 도메인(weekly_plans·plan_blocks·tasks)을
 * JdbcTemplate로만 접촉한다 — JPA 엔티티는 후속 스토리 소유라 침범하지 않는다. PLAN 스토리 킥오프 시
 * 이 클래스는 plan 패키지로 이관된다(인터페이스 불변).
 *
 * <p>호출자(ProjectService.delete)와 같은 tx·같은 커넥션에서 실행된다. 단, JdbcTemplate SQL은 Hibernate
 * auto-flush를 트리거하지 않으므로 호출자가 delete 직후 <b>명시적 flush</b>로 cascade를 DB에 반영시킨 뒤
 * {@link #recalculate}를 불러야 남은 블록을 정확히 합산한다(C1).
 */
@Component
public class JdbcWeeklyPlanTotalsRecalculator implements WeeklyPlanTotalsRecalculator {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWeeklyPlanTotalsRecalculator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<UUID> captureAffectedWeeklyPlanIds(UUID projectId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT pb.weekly_plan_id
                  FROM plan_blocks pb
                  JOIN tasks t ON pb.task_id = t.task_id
                 WHERE t.project_id = ?
                """, UUID.class, projectId);
    }

    @Override
    public List<UUID> captureAffectedWeeklyPlanIdsByTask(UUID taskId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT weekly_plan_id FROM plan_blocks WHERE task_id = ?", UUID.class, taskId);
    }

    @Override
    public void recalculate(List<UUID> weeklyPlanIds) {
        if (weeklyPlanIds == null || weeklyPlanIds.isEmpty()) {
            return;
        }
        for (UUID weeklyPlanId : weeklyPlanIds) {
            jdbcTemplate.update("""
                    UPDATE weekly_plans w
                       SET total_planned_minutes = COALESCE((
                               SELECT SUM(EXTRACT(EPOCH FROM (pb.end_at - pb.start_at)) / 60)::int
                                 FROM plan_blocks pb
                                WHERE pb.weekly_plan_id = w.weekly_plan_id), 0)
                     WHERE w.weekly_plan_id = ?
                    """, weeklyPlanId);
        }
    }
}
