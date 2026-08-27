package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 블록 해제·삭제 API 통합 테스트 (PLAN-16·18 / TUT-07) — {@code DELETE /plan-blocks/{blockId}}.
 * 배치({@code createBlock})의 부작용을 역방향으로 되돌리는지 고정한다: TASK 남은블록 0→UNASSIGNED 복귀,
 * SCHEDULE 일정 연쇄 삭제, total 재계산, 소유 스코프(404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PlanBlockDeleteApiTest {

    private static final UUID MAIN = UUID.fromString("eeee1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeee1111-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3);
    private static final String START = "2026-08-03T00:00:00Z";
    private static final String END = "2026-08-03T03:00:00Z";   // 180분
    private static final String START2 = "2026-08-03T04:00:00Z";
    private static final String END2 = "2026-08-03T05:00:00Z";  // 60분

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
        jdbc.update("DELETE FROM schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        project = insertProject(MAIN, "프로젝트");
    }

    // ---------- TASK 블록 해제 (PLAN-16) ----------

    @Test
    @DisplayName("TASK 블록 삭제 → 204 · 마지막 블록이면 태스크 UNASSIGNED 복귀 · total 0 재계산")
    void deleteTaskBlockReturnsTaskToUnassigned() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END); // 배치 → IN_PROGRESS·total 180

        mockMvc.perform(delete("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, task))
                .isEqualTo("UNASSIGNED"); // 마지막 블록 제거 → 복귀 (TT-2)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE plan_block_id = ?",
                Integer.class, blockId)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isZero(); // total 재계산
    }

    @Test
    @DisplayName("TASK 블록 삭제 — 다른 블록이 남아 있으면 태스크는 IN_PROGRESS 유지(불변식)")
    void deleteTaskBlockKeepsInProgressWhenOtherBlockRemains() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID block1 = placeTaskBlock(plan, task, START, END);   // 180
        placeTaskBlock(plan, task, START2, END2);               // 같은 태스크 2번째 블록 60

        mockMvc.perform(delete("/api/v1/plan-blocks/" + block1).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, task))
                .isEqualTo("IN_PROGRESS"); // 아직 블록 1개 남음 → 유지
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(60); // 남은 블록만 재계산
    }

    // ---------- SCHEDULE 블록 삭제 (PLAN-18) ----------

    @Test
    @DisplayName("SCHEDULE 블록 삭제 → 204 · 연결 일정(schedules 행) 연쇄 삭제 · total 0")
    void deleteScheduleBlockCascadesSchedule() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID blockId = placeScheduleBlock(plan, "병원 예약");

        UUID scheduleId = UUID.fromString(jdbc.queryForObject(
                "SELECT schedule_id FROM plan_blocks WHERE plan_block_id = ?", String.class, blockId));

        mockMvc.perform(delete("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedules WHERE schedule_id = ?",
                Integer.class, scheduleId)).isZero(); // 일정 연쇄 삭제
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isZero();
    }

    // ---------- 확정 편집 재개 ----------

    @Test
    @DisplayName("확정(CONFIRMED) 계획의 블록 삭제 → DRAFT 복귀 · confirmed_at 해제")
    void deleteReopensConfirmedPlanToDraft() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END);
        // 배치 후 확정 상태로 강제 전환(편집 재개 검증용)
        jdbc.update("UPDATE weekly_plans SET status = 'CONFIRMED', confirmed_at = ? WHERE weekly_plan_id = ?",
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC), plan);

        mockMvc.perform(delete("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM weekly_plans WHERE weekly_plan_id = ?", String.class, plan))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM weekly_plans WHERE weekly_plan_id = ?",
                Object.class, plan)).isNull();
    }

    // ---------- 소유 스코프 ----------

    @Test
    @DisplayName("없는 블록 → 404")
    void deleteUnknownBlock() throws Exception {
        mockMvc.perform(delete("/api/v1/plan-blocks/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 블록 → 404 (존재 은닉, 남의 것 안 지워짐)")
    void deleteOtherUserBlockHidden() throws Exception {
        UUID othersPlan = insertWeeklyPlan(OTHER, WEEK, "DRAFT", null);
        UUID othersProject = insertProject(OTHER, "타인프로젝트");
        UUID othersTask = insertTask(othersProject, "타인태스크", TaskStatus.UNASSIGNED);
        UUID othersBlock = placeTaskBlock(OTHER, othersPlan, othersTask, START, END);

        mockMvc.perform(delete("/api/v1/plan-blocks/" + othersBlock).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE plan_block_id = ?",
                Integer.class, othersBlock)).isEqualTo(1); // 남의 것 그대로
    }

    // ---------- fixtures ----------

    private UUID placeTaskBlock(UUID plan, UUID task, String start, String end) throws Exception {
        return placeTaskBlock(MAIN, plan, task, start, end);
    }

    private UUID placeTaskBlock(UUID userId, UUID plan, UUID task, String start, String end) throws Exception {
        String body = "{\"blockType\":\"TASK\",\"taskId\":\"" + task + "\","
                + "\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\"}";
        return extractBlockId(block(userId, plan, body).andExpect(status().isCreated()));
    }

    private UUID placeScheduleBlock(UUID plan, String title) throws Exception {
        String body = "{\"blockType\":\"SCHEDULE\",\"schedule\":{\"title\":\"" + title + "\",\"estimatedMinutes\":60},"
                + "\"startAt\":\"" + START + "\",\"endAt\":\"" + END + "\"}";
        return extractBlockId(block(MAIN, plan, body).andExpect(status().isCreated()));
    }

    private UUID extractBlockId(ResultActions actions) throws Exception {
        String json = actions.andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.data.planBlockId"));
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
                """, id, userId, name, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title, TaskStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, 0, ?)
                """, id, projectId, title, status.name(), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart, String status, Instant confirmedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, 0, ?)
                """, id, userId, weekStart, weekStart.plusDays(6), status,
                confirmedAt == null ? null : OffsetDateTime.ofInstant(confirmedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
