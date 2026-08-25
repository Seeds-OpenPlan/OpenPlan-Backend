package com.openplan.backend.project.controller;

import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import com.openplan.backend.task.domain.TaskStatus;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 복제 프리뷰 API 통합 테스트 (PROJ-11) — {@code GET /projects/{projectId}/duplication-preview}.
 * 개요(이름·설명·태스크 수·WBS 수·note) 반환·상태 무관 허용·소유(404)를 고정한다. 저장 없는 조회.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectDuplicationPreviewApiTest {

    private static final UUID MAIN = UUID.fromString("a1b1c1d1-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("a1b1c1d1-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM wbs_items WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("복제 프리뷰 → 200 · 이름·설명·태스크 수·WBS 수·note")
    void preview() throws Exception {
        UUID project = insertProject(MAIN, "마케팅", "3분기 캠페인", "IN_PROGRESS");
        UUID t1 = insertTask(project, "태스크1");
        UUID t2 = insertTask(project, "태스크2");
        insertTask(project, "태스크3");
        insertWbs(project, t1);
        insertWbs(project, t2); // 태스크 3개 중 2개에 WBS

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("마케팅"))
                .andExpect(jsonPath("$.data.description").value("3분기 캠페인"))
                .andExpect(jsonPath("$.data.taskCount").value(3))
                .andExpect(jsonPath("$.data.wbsItemCount").value(2))
                .andExpect(jsonPath("$.data.note").exists());
    }

    @Test
    @DisplayName("태스크·WBS 없는 프로젝트 → 0 카운트")
    void previewEmptyProject() throws Exception {
        UUID project = insertProject(MAIN, "빈프로젝트", null, "IN_PROGRESS");

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskCount").value(0))
                .andExpect(jsonPath("$.data.wbsItemCount").value(0));
    }

    @Test
    @DisplayName("종료(CLOSED) 프로젝트도 프리뷰 가능(조회라 상태 무관)")
    void previewClosedProject() throws Exception {
        UUID project = insertProject(MAIN, "종료프로젝트", "설명", "CLOSED");

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("종료프로젝트"));
    }

    @Test
    @DisplayName("없는 프로젝트 → 404")
    void previewNotFound() throws Exception {
        mockMvc.perform(get(path(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 프로젝트 → 404 (존재 은닉)")
    void previewOtherUserHidden() throws Exception {
        UUID project = insertProject(OTHER, "남의프로젝트", null, "IN_PROGRESS");

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound());
    }

    // ---------- fixtures ----------

    private String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/duplication-preview";
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

    private UUID insertProject(UUID userId, String name, String description, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, NULL, ?, 0, ?)
                """, id, userId, name, description, status,
                "CLOSED".equals(status) ? OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC) : null,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, 60, NULL, NULL, ?, 0, ?)
                """, id, projectId, title, TaskStatus.UNASSIGNED.name(),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertWbs(UUID projectId, UUID taskId) {
        jdbc.update("""
                INSERT INTO wbs_items (wbs_item_id, project_id, task_id, start_date, end_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, taskId,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
