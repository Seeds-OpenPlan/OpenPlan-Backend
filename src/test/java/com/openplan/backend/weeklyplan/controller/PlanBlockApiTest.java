package com.openplan.backend.weeklyplan.controller;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 블록 배치 API 통합 테스트 (ST-B2-08 / PLAN-06·07). 핵심: TASK 블록 배치 시 ① 태스크 UNASSIGNED→IN_PROGRESS 전이
 * ② 주 total 재계산(값 단언) ③ 확정 계획 DRAFT 복귀. 완료 태스크 금지·검증·소유 404도 고정.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PlanBlockApiTest {

    private static final UUID MAIN = UUID.fromString("cccc1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccc1111-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3);
    private static final String START = "2026-08-03T00:00:00Z";
    private static final String END = "2026-08-03T03:00:00Z"; // 180분

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID project;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        project = insertProject(MAIN, "프로젝트");
    }

    // ---------- 핵심: 배치 + 상태 전이 + total 재계산 ----------

    @Test
    @DisplayName("TASK 블록 배치 → 201 · 미배치 태스크 IN_PROGRESS 전이 · 주 total=180분 값 단언")
    void placeTaskBlock() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "미배치 태스크", TaskStatus.UNASSIGNED);

        block(MAIN, plan, taskBody(task))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planBlockId").exists())
                .andExpect(jsonPath("$.data.weeklyPlanId").value(plan.toString()))
                .andExpect(jsonPath("$.data.taskId").value(task.toString()))
                .andExpect(jsonPath("$.data.blockType").value("TASK"))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

        // ① 태스크 전이: UNASSIGNED → IN_PROGRESS
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, task))
                .isEqualTo("IN_PROGRESS");
        // ② 주 total 재계산: 180분 (값 단언)
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(180);
    }

    @Test
    @DisplayName("결정 C — 확정(CONFIRMED) 계획에 블록 배치 → DRAFT 복귀 · confirmed_at 해제")
    void confirmedPlanReopensToDraft() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "CONFIRMED", BASE);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);

        block(MAIN, plan, taskBody(task)).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT status FROM weekly_plans WHERE weekly_plan_id = ?", String.class, plan))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM weekly_plans WHERE weekly_plan_id = ?",
                Object.class, plan)).isNull();
        // total 재계산도 정상 반영(확정 편집 시 stale 0으로 덮이지 않음 — C1)
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(180);
    }

    // ---------- 결정 B · 검증 ----------

    @Test
    @DisplayName("결정 B — 완료(COMPLETED) 태스크 배치 → 422")
    void completedTaskForbidden() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "완료 태스크", TaskStatus.COMPLETED);

        block(MAIN, plan, taskBody(task))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("taskId"))
                // 문구가 errors.properties(validation.taskId.completed)에서 해석되는지 고정
                .andExpect(jsonPath("$.error.details.fields[0].message").value("완료된 태스크는 배치할 수 없습니다."));
    }

    @Test
    @DisplayName("startAt >= endAt → 422 E-PLAN-002")
    void invalidRange() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);

        block(MAIN, plan, "{\"blockType\":\"TASK\",\"taskId\":\"" + task + "\","
                + "\"startAt\":\"" + END + "\",\"endAt\":\"" + START + "\"}") // start>end
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PLAN-002"));
    }

    @Test
    @DisplayName("SCHEDULE 블록(이번 미지원) → 422 · taskId 누락 → 422")
    void unsupportedOrMissing() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);

        block(MAIN, plan, "{\"blockType\":\"SCHEDULE\",\"startAt\":\"" + START + "\",\"endAt\":\"" + END + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("blockType"));

        block(MAIN, plan, "{\"blockType\":\"TASK\",\"startAt\":\"" + START + "\",\"endAt\":\"" + END + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("taskId"));
    }

    // ---------- 소유 스코프 ----------

    @Test
    @DisplayName("부재·타인 계획 → 404 · 타인 태스크 → 404")
    void ownershipNotFound() throws Exception {
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);

        block(MAIN, UUID.randomUUID(), taskBody(task)) // 없는 계획
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID othersPlan = insertWeeklyPlan(OTHER, WEEK, "DRAFT", null);
        block(MAIN, othersPlan, taskBody(task)).andExpect(status().isNotFound()); // 타인 계획

        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID othersProject = insertProject(OTHER, "타인프로젝트");
        UUID othersTask = insertTask(othersProject, "타인태스크", TaskStatus.UNASSIGNED);
        block(MAIN, plan, taskBody(othersTask)).andExpect(status().isNotFound()); // 타인 태스크
    }

    // ---------- fixtures ----------

    private String taskBody(UUID taskId) {
        return "{\"blockType\":\"TASK\",\"taskId\":\"" + taskId + "\","
                + "\"startAt\":\"" + START + "\",\"endAt\":\"" + END + "\"}";
    }

    private ResultActions block(UUID userId, UUID planId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/weekly-plans/" + planId + "/blocks")
                .header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
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

    private UUID insertTask(UUID projectId, String title, TaskStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, 0, ?)
                """,
                id, projectId, title, status.name(), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart, String status, Instant confirmedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, 0, ?)
                """,
                id, userId, weekStart, weekStart.plusDays(6), status,
                confirmedAt == null ? null : OffsetDateTime.ofInstant(confirmedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
