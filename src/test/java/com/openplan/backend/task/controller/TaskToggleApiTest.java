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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 완료 토글 API 통합 테스트 (PLAN-13/14 / EP-5 · AC-S-1~6). 지명 테스트 [T4](미러 동일 tx 값 단언)·
 * [T5](TT-5 UNASSIGNED 착지)·[T6](CLOSED ∩ no-op 교차)를 포함한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskToggleApiTest {

    private static final UUID MAIN = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID inProgress;
    private UUID closed;
    private UUID weeklyPlan;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE task_id IN "
                + "(SELECT t.task_id FROM tasks t JOIN projects p ON p.project_id=t.project_id "
                + "WHERE p.user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);

        inProgress = insertProject(MAIN, "진행중", ProjectStatus.IN_PROGRESS, null);
        closed = insertProject(MAIN, "종료", ProjectStatus.CLOSED, BASE);
        weeklyPlan = insertWeeklyPlan(MAIN);
    }

    // ---------- AC-S-1 [T4] 완료 전환 + 미러 동일 tx ----------

    @Test
    @DisplayName("AC-S-1 [T4] IN_PROGRESS+블록 2건 → 완료 → COMPLETED · 블록 2건 status='COMPLETED' 값 단언 · version+1")
    void completeMirrorsBlocks() throws Exception {
        UUID task = insertTask(inProgress, "진행태스크", TaskStatus.IN_PROGRESS, 0);
        insertBlock(task, "SCHEDULED");
        insertBlock(task, "SCHEDULED");

        toggle(task, "{\"completed\":true,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 같은 tx 미러 — 블록 2건 전부 COMPLETED (값 단언)
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM plan_blocks WHERE task_id = ?", String.class, task);
        assertThat(statuses).containsExactlyInAnyOrder("COMPLETED", "COMPLETED");
    }

    // ---------- AC-S-2 미완료 되돌리기 (블록≥1 → IN_PROGRESS) ----------

    @Test
    @DisplayName("AC-S-2 COMPLETED+블록 1건 → 미완료 → IN_PROGRESS · 블록 'SCHEDULED' 복원")
    void reopenWithBlocks() throws Exception {
        UUID task = insertTask(inProgress, "완료태스크", TaskStatus.COMPLETED, 0);
        insertBlock(task, "COMPLETED");

        toggle(task, "{\"completed\":false,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(1));

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM plan_blocks WHERE task_id = ?", String.class, task);
        assertThat(statuses).containsExactly("SCHEDULED");
    }

    // ---------- AC-S-3 [T5] 블록 0 → UNASSIGNED 착지 ----------

    @Test
    @DisplayName("AC-S-3 [T5] COMPLETED+블록 0건 → 미완료 → UNASSIGNED 착지 (오류 아님·미러 미발동)")
    void reopenWithoutBlocksLandsUnassigned() throws Exception {
        UUID task = insertTask(inProgress, "완료-블록없음", TaskStatus.COMPLETED, 0);

        toggle(task, "{\"completed\":false,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    // ---------- AC-S-4 TT-6 불허 ----------

    @Test
    @DisplayName("AC-S-4 TT-6: UNASSIGNED에 완료 요청 → 422 E-PROJ-003 · 상태 불변")
    void completeUnassignedForbidden() throws Exception {
        UUID task = insertTask(inProgress, "미배치", TaskStatus.UNASSIGNED, 0);

        toggle(task, "{\"completed\":true,\"version\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-003"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("status"));

        String status = jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, task);
        assertThat(status).isEqualTo("UNASSIGNED"); // 불변
    }

    // ---------- AC-S-5 멱등 no-op (version 검사보다 먼저) ----------

    @Test
    @DisplayName("AC-S-5 동일 완료여부 → 200 no-op · version 미증가 · stale version이어도 409 아님(version 검사 전 단락)")
    void idempotentNoop() throws Exception {
        UUID task = insertTask(inProgress, "이미완료", TaskStatus.COMPLETED, 3);

        // completed=true == 이미 COMPLETED → no-op. stale version=0인데도 409 아님(version 검사 전 단락)
        toggle(task, "{\"completed\":true,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.version").value(3)); // 미증가
    }

    // ---------- AC-S-6 낙관락·400·404·CLOSED ----------

    @Test
    @DisplayName("AC-S-6 stale version → 409+latest · completed/version 누락 → 400 · 부재·타인 → 404")
    void guards() throws Exception {
        UUID task = insertTask(inProgress, "서버v3", TaskStatus.IN_PROGRESS, 3);

        toggle(task, "{\"completed\":true,\"version\":0}") // 실제 전이 + stale
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"))
                .andExpect(jsonPath("$.error.details.latest.version").value(3));

        toggle(task, "{\"version\":0}").andExpect(status().isBadRequest())      // completed 누락
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
        toggle(task, "{\"completed\":true}").andExpect(status().isBadRequest()); // version 누락

        toggle(UUID.randomUUID(), "{\"completed\":true,\"version\":0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("[T6] CLOSED ∩ no-op 교차 — CLOSED 프로젝트 COMPLETED 태스크에 완료 요청(no-op) → 422 E-PROJ-005 (CLOSED 먼저)")
    void closedGuardBeatsNoop() throws Exception {
        UUID task = insertTask(closed, "종료-완료태스크", TaskStatus.COMPLETED, 0);

        // completed=true == 이미 COMPLETED (no-op 대상)이지만 CLOSED 가드가 먼저 → 200 no-op 아님, 422
        toggle(task, "{\"completed\":true,\"version\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("project.status"));
    }

    // ---------- fixtures ----------

    private ResultActions toggle(UUID taskId, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/tasks/" + taskId + "/status")
                .header("X-Dev-User", MAIN.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    private UUID insertProject(UUID userId, String name, ProjectStatus status, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, ?, NULL, ?, 0, ?)
                """,
                id, userId, name, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', 0, ?)
                """,
                id, userId, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title, TaskStatus status, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, ?, ?)
                """,
                id, projectId, title, status.name(), version, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertBlock(UUID taskId, String status) {
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, ?, NULL, 'TASK', ?, ?, ?, ?)
                """,
                UUID.randomUUID(), weeklyPlan, taskId,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC),
                status, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
