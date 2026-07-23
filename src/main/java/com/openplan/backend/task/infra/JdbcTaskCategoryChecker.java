package com.openplan.backend.task.infra;

import com.openplan.backend.task.service.port.TaskCategoryChecker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link TaskCategoryChecker} 잠정 구현 (TB-6). 카테고리 도메인 테이블(task_categories)을 JdbcTemplate로만
 * 접촉한다 — JPA 엔티티는 ST-B2-04 소유라 침범하지 않는다. ST-B2-04 킥오프 시 이 클래스는 카테고리
 * 패키지로 이관된다(인터페이스 불변).
 *
 * <p>호출자(TaskService)와 같은 tx·같은 커넥션에서 실행된다(REQUIRED).
 */
@Component
public class JdbcTaskCategoryChecker implements TaskCategoryChecker {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTaskCategoryChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsOwned(UUID categoryId, UUID userId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM task_categories WHERE task_category_id = ? AND user_id = ?)",
                Boolean.class, categoryId, userId);
        return Boolean.TRUE.equals(exists);
    }
}
