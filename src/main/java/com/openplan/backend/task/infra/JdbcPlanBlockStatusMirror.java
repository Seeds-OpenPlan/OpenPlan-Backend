package com.openplan.backend.task.infra;

import com.openplan.backend.task.domain.TaskStatus;
import com.openplan.backend.task.service.port.PlanBlockStatusMirror;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link PlanBlockStatusMirror} 잠정 구현 (TB-1). PLAN 도메인 테이블(plan_blocks)을 JdbcTemplate로만 접촉한다 —
 * JPA 엔티티는 ST-B2-08 소유라 침범하지 않는다. ST-B2-08 킥오프 시 plan 패키지로 이관된다(인터페이스 불변).
 *
 * <p>호출자(TaskService.toggleCompletion)와 같은 tx·같은 커넥션에서 실행된다(REQUIRED — 태스크 UPDATE와
 * 블록 미러의 원자성). SSM §3 매핑(COMPLETED→'COMPLETED', IN_PROGRESS→'SCHEDULED')은 여기 Java에서 치환한다.
 */
@Component
public class JdbcPlanBlockStatusMirror implements PlanBlockStatusMirror {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPlanBlockStatusMirror(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void mirrorStatus(UUID taskId, TaskStatus taskStatus) {
        String blockStatus = switch (taskStatus) {
            case COMPLETED -> "COMPLETED";
            case IN_PROGRESS -> "SCHEDULED";
            case UNASSIGNED -> throw new IllegalArgumentException(
                    "UNASSIGNED는 블록 미러 대상이 아니다(블록 0 상태 — SSM §3)");
        };
        jdbcTemplate.update("UPDATE plan_blocks SET status = ? WHERE task_id = ?", blockStatus, taskId);
    }

    @Override
    public boolean hasBlocks(UUID taskId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM plan_blocks WHERE task_id = ?)", Boolean.class, taskId);
        return Boolean.TRUE.equals(exists);
    }
}
