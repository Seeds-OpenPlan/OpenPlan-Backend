package com.openplan.backend.executionlog.infra;

import com.openplan.backend.executionlog.service.port.PlanBlockOwnershipChecker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link PlanBlockOwnershipChecker} 잠정 구현. PLAN 도메인 테이블({@code plan_blocks})을 JdbcTemplate로만
 * 접촉한다 — JPA 엔티티는 ST-B2-08 소유라 침범하지 않는다(task 패키지 {@code JdbcPlanBlockStatusMirror}와 동일 방식).
 *
 * <p>소유 판정 경로는 {@code plan_blocks → weekly_plans.user_id} 다. {@code plan_blocks}에 user_id가 없어
 * 주간 계획안을 거쳐야 한다.
 */
@Component
public class JdbcPlanBlockOwnershipChecker implements PlanBlockOwnershipChecker {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPlanBlockOwnershipChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isOwnedBy(UUID planBlockId, UUID userId) {
        Boolean owned = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                      FROM plan_blocks pb
                      JOIN weekly_plans wp ON wp.weekly_plan_id = pb.weekly_plan_id
                     WHERE pb.plan_block_id = ? AND wp.user_id = ?)
                """, Boolean.class, planBlockId, userId);
        return Boolean.TRUE.equals(owned);
    }
}
