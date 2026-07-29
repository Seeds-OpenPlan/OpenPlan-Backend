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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 내 태스크 목록 API 통합 테스트 (PROJ-16 / EP-1 · AC-L-1~4). 전체/개별 필터·미정의값 422·
 * 페이지 규약·정렬 고정(created_at DESC, task_id DESC)·소유 404를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskListApiTest {

    private static final UUID MAIN = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID project;      // MAIN 소유, 태스크 4건
    private UUID emptyProject; // MAIN 소유, 태스크 0건
    private UUID othersProject;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);

        project = insertProject(MAIN, "메인", ProjectStatus.IN_PROGRESS);
        emptyProject = insertProject(MAIN, "빈프로젝트", ProjectStatus.IN_PROGRESS);
        othersProject = insertProject(OTHER, "타인", ProjectStatus.IN_PROGRESS);

        // created_at 오름차순으로 심는다: T1(가장 오래) … T4(최신). 응답은 DESC라 T4가 먼저.
        insertTask(project, "T1", TaskStatus.UNASSIGNED, BASE.plusSeconds(0));
        insertTask(project, "T2", TaskStatus.UNASSIGNED, BASE.plusSeconds(60));
        insertTask(project, "T3", TaskStatus.IN_PROGRESS, BASE.plusSeconds(120));
        insertTask(project, "T4", TaskStatus.COMPLETED, BASE.plusSeconds(180));
    }

    @Test
    @DisplayName("AC-L-1 status 생략 → 전체 4건 · status 원값·version 동봉 · 정렬 created_at DESC")
    void listAll() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.meta.page.number").value(1))
                .andExpect(jsonPath("$.meta.page.totalElements").value(4))
                .andExpect(jsonPath("$.meta.page.totalPages").value(1))
                // 정렬: 최신(T4)이 선두
                .andExpect(jsonPath("$.data[0].title").value("T4"))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].version").value(0))
                .andExpect(jsonPath("$.data[3].title").value("T1"));
    }

    @Test
    @DisplayName("AC-L-2 status=COMPLETED → 1건만 · 미정의값(DONE) → 422 E-COM-009")
    void filterByStatus() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks")
                        .param("status", "COMPLETED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("T4"))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1));

        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks")
                        .param("status", "DONE").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    @Test
    @DisplayName("AC-L-3 page/size 규약 위반 → 400 · 범위 밖 page → 빈 목록 성공(오류 아님)")
    void paginationRules() throws Exception {
        expect400("page", "0");
        expect400("size", "0");
        expect400("size", "101");

        // 범위 밖 page=5 → 빈 data + 정상 meta 200
        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks")
                        .param("page", "5").param("size", "20").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page.totalElements").value(4));
    }

    @Test
    @DisplayName("AC-L-3 size=2 페이지네이션 — 1페이지 최신 2건, 2페이지 나머지")
    void paging() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks")
                        .param("page", "1").param("size", "2").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("T4"))
                .andExpect(jsonPath("$.data[1].title").value("T3"))
                .andExpect(jsonPath("$.meta.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks")
                        .param("page", "2").param("size", "2").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("T2"))
                .andExpect(jsonPath("$.data[1].title").value("T1"));
    }

    @Test
    @DisplayName("AC-L-4 부재·타인 projectId → 404 · 빈 프로젝트 → 빈 목록 성공")
    void scopeAndEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/tasks").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        mockMvc.perform(get("/api/v1/projects/" + othersProject + "/tasks").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/" + emptyProject + "/tasks").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page.totalElements").value(0));
    }

    // ---------- fixtures ----------

    private void expect400(String param, String value) throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + project + "/tasks")
                        .param(param, value).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
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

    private void insertTask(UUID projectId, String title, TaskStatus status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, 0, ?)
                """,
                UUID.randomUUID(), projectId, title, status.name(),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }
}
