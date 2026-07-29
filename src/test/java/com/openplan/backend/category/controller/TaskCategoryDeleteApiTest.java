package com.openplan.backend.category.controller;

import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카테고리 삭제 API 통합 테스트 (ST-B2-04 / SC-01 · AC②). 핵심: 삭제 시 연결된 태스크의 category_id가
 * FK ON DELETE SET NULL로 자동 '없음' 전환되는지(앱 코드 없이) <b>값 단언</b>. 소유자 404·멱등도 고정.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskCategoryDeleteApiTest {

    private static final String PATH = "/api/v1/task-categories";
    private static final UUID MAIN = UUID.fromString("aaaa3333-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("aaaa3333-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM task_categories WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- AC② 핵심 값 단언 ----------

    @Test
    @DisplayName("AC② 삭제 → 연결 태스크의 category_id가 FK로 자동 null 전환 · 태스크는 살아있음 (앱 코드 없이)")
    void deleteSetsTaskCategoryNull() throws Exception {
        UUID category = insertCategory(MAIN, "업무", 0);
        UUID project = insertProject(MAIN, "프로젝트");
        UUID task = insertTask(project, category, "카테고리 붙은 태스크");

        // 사전: 태스크의 category_id = 그 카테고리
        UUID before = jdbc.queryForObject(
                "SELECT category_id FROM tasks WHERE task_id = ?", UUID.class, task);
        assertThat(before).isEqualTo(category);

        del(MAIN, category).andExpect(status().isNoContent());

        // 사후: 카테고리 제거 · 태스크는 살아있고 category_id = null (FK SET NULL, 앱 갱신 없이)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_categories WHERE task_category_id = ?",
                Integer.class, category)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE task_id = ?",
                Integer.class, task)).isEqualTo(1);
        UUID after = jdbc.queryForObject(
                "SELECT category_id FROM tasks WHERE task_id = ?", UUID.class, task);
        assertThat(after).isNull();
    }

    // ---------- 정상 삭제 · 소유자 · 멱등 ----------

    @Test
    @DisplayName("정상 삭제 → 204 · 목록에서 사라짐")
    void deleteOk() throws Exception {
        UUID category = insertCategory(MAIN, "삭제대상", 0);
        del(MAIN, category).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_categories WHERE task_category_id = ?",
                Integer.class, category)).isZero();
    }

    @Test
    @DisplayName("부재·타인 → 404 · 타인 것은 미삭제 · 재삭제 → 404")
    void notFoundAndScope() throws Exception {
        del(MAIN, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID othersCategory = insertCategory(OTHER, "타인것", 0);
        del(MAIN, othersCategory).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_categories WHERE task_category_id = ?",
                Integer.class, othersCategory)).isEqualTo(1); // 미삭제

        UUID mine = insertCategory(MAIN, "내것", 0);
        del(MAIN, mine).andExpect(status().isNoContent());
        del(MAIN, mine).andExpect(status().isNotFound()); // 재삭제 → 404
    }

    // ---------- fixtures ----------

    private ResultActions del(UUID userId, UUID categoryId) throws Exception {
        return mockMvc.perform(delete(PATH + "/" + categoryId).header("X-Dev-User", userId.toString()));
    }

    private void seedUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
                VALUES (?, ?, 'x', 'LOCAL', true, 'ACTIVE') ON CONFLICT (user_id) DO NOTHING
                """, id, id + "@test.local");
        jdbc.update("""
                INSERT INTO user_profiles (profile_id, user_id, name, purpose, timezone, week_start_day)
                VALUES (?, ?, '테스트', '테스트', 'Asia/Seoul', 'MON') ON CONFLICT (user_id) DO NOTHING
                """, UUID.randomUUID(), id);
    }

    private UUID insertCategory(UUID userId, String name, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task_categories (task_category_id, user_id, name, sort_order, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, userId, name, sortOrder, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertProject(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, 'IN_PROGRESS', NULL, NULL, 0, ?)
                """,
                id, userId, name, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, UUID categoryId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, 'UNASSIGNED', 0, ?)
                """,
                id, projectId, categoryId, title, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
