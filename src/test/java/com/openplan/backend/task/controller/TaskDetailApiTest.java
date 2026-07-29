package com.openplan.backend.task.controller;

import com.openplan.backend.project.domain.ProjectStatus;
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
 * 태스크 단건 조회 API 통합 테스트 (PROJ-18 편집 폼 로딩 / EP-3 · AC-R-1~2). 전 필드 반환과
 * 소유 체인 404 은닉(부재·타인)을 고정한다. 소유는 tasks→projects.user_id 조인으로만 판정(D-16).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskDetailApiTest {

    private static final String PATH = "/api/v1/tasks";
    private static final UUID MAIN = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID myProject;
    private UUID othersProject;
    private UUID myCategory;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM task_categories WHERE user_id IN (?, ?)", MAIN, OTHER);

        myProject = insertProject(MAIN, "내 프로젝트", ProjectStatus.IN_PROGRESS);
        othersProject = insertProject(OTHER, "타인 프로젝트", ProjectStatus.IN_PROGRESS);
        myCategory = insertCategory(MAIN, "내 카테고리");
    }

    @Test
    @DisplayName("AC-R-1 단건 조회 → 200 · 전 필드 + version 반환")
    void detailOk() throws Exception {
        UUID taskId = insertTask(myProject, myCategory, "상세 태스크", "메모내용",
                45, 2, LocalDate.of(2099, 12, 31), TaskStatus.IN_PROGRESS, 3);

        mockMvc.perform(get(PATH + "/" + taskId).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.data.projectId").value(myProject.toString()))
                .andExpect(jsonPath("$.data.categoryId").value(myCategory.toString()))
                .andExpect(jsonPath("$.data.title").value("상세 태스크"))
                .andExpect(jsonPath("$.data.memo").value("메모내용"))
                .andExpect(jsonPath("$.data.estimatedMinutes").value(45))
                .andExpect(jsonPath("$.data.priority").value(2))
                .andExpect(jsonPath("$.data.dueDate").value("2099-12-31"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    @DisplayName("AC-R-1 선택 필드 null(미분류·무기한 등)은 응답에서 생략 — NON_NULL")
    void detailOmitsNulls() throws Exception {
        UUID taskId = insertTask(myProject, null, "미니멀", null,
                null, null, null, TaskStatus.UNASSIGNED, 0);

        mockMvc.perform(get(PATH + "/" + taskId).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("미니멀"))
                .andExpect(jsonPath("$.data.categoryId").doesNotExist())
                .andExpect(jsonPath("$.data.memo").doesNotExist())
                .andExpect(jsonPath("$.data.estimatedMinutes").doesNotExist())
                .andExpect(jsonPath("$.data.dueDate").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("UNASSIGNED"));
    }

    @Test
    @DisplayName("AC-R-2 부재 taskId → 404 E-COM-004")
    void notFoundAbsent() throws Exception {
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("AC-R-2 타인 소유 taskId → 404 (부재와 구분 불가 은닉)")
    void notFoundOtherOwner() throws Exception {
        UUID othersTask = insertTask(othersProject, null, "타인 태스크", null,
                null, null, null, TaskStatus.UNASSIGNED, 0);

        mockMvc.perform(get(PATH + "/" + othersTask).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

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

    private UUID insertProject(UUID userId, String name, ProjectStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, ?, NULL, NULL, 0, ?)
                """,
                id, userId, name, status.name(), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertCategory(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task_categories (task_category_id, user_id, name) VALUES (?, ?, ?)",
                id, userId, name);
        return id;
    }

    private UUID insertTask(UUID projectId, UUID categoryId, String title, String memo,
                            Integer estimatedMinutes, Integer priority, LocalDate dueDate,
                            TaskStatus status, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, projectId, categoryId, title, memo, estimatedMinutes, priority, dueDate,
                status.name(), version, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
