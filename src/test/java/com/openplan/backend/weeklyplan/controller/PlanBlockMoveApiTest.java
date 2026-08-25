package com.openplan.backend.weeklyplan.controller;

import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 블록 이동·시간 조정 API 통합 테스트 (PLAN-19·20) — {@code PATCH /plan-blocks/{blockId}}.
 * 시각 조정·부분 수정·주차 이동(대상 주 get-or-create·양쪽 total 재계산)·검증(422)·소유(404)를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PlanBlockMoveApiTest {

    private static final UUID MAIN = UUID.fromString("ffff1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("ffff1111-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3);      // 월요일
    private static final LocalDate NEXT_WEEK = LocalDate.of(2026, 8, 10);
    private static final String START = "2026-08-03T00:00:00Z";
    private static final String END = "2026-08-03T03:00:00Z";           // 180분

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

    // ---------- 시각 조정 (PLAN-19) ----------

    @Test
    @DisplayName("시각 조정 → 200 · start/end 갱신 · 주 total 재계산(180→60)")
    void adjustTime() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END); // 180분

        mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-08-03T01:00:00Z\",\"endAt\":\"2026-08-03T02:00:00Z\"}")) // 60분
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planBlockId").value(blockId.toString()))
                .andExpect(jsonPath("$.data.startAt").value("2026-08-03T01:00:00Z"))
                .andExpect(jsonPath("$.data.endAt").value("2026-08-03T02:00:00Z"));

        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(60);
    }

    @Test
    @DisplayName("부분 수정 — endAt만 보내면 startAt은 기존 유지")
    void partialMoveKeepsStart() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END);

        mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endAt\":\"2026-08-03T04:00:00Z\"}")) // start 유지(00:00), end만 04:00 → 240분
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startAt").value("2026-08-03T00:00:00Z")) // 유지
                .andExpect(jsonPath("$.data.endAt").value("2026-08-03T04:00:00Z"));
    }

    // ---------- 주차 이동 (PLAN-20) ----------

    @Test
    @DisplayName("주차 이동 → 200 · 대상 주 계획 get-or-create · 양쪽 주 total 재계산(원본 0·대상 180)")
    void moveToAnotherWeekCreatesTargetPlan() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END); // 원본 주 180

        mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-08-10T00:00:00Z\",\"endAt\":\"2026-08-10T03:00:00Z\","
                                + "\"targetWeekStartDate\":\"2026-08-10\"}"))
                .andExpect(status().isOk());

        // 대상 주 계획이 새로 생겼고 블록이 그쪽 소속으로 이동
        UUID targetPlan = UUID.fromString(jdbc.queryForObject(
                "SELECT weekly_plan_id FROM weekly_plans WHERE user_id = ? AND week_start_date = ?",
                String.class, MAIN, NEXT_WEEK));
        assertThat(UUID.fromString(jdbc.queryForObject(
                "SELECT weekly_plan_id FROM plan_blocks WHERE plan_block_id = ?", String.class, blockId)))
                .isEqualTo(targetPlan);

        // 양쪽 total 재계산: 원본 0, 대상 180
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, targetPlan)).isEqualTo(180);
    }

    @Test
    @DisplayName("주차 이동 — 대상 주 계획이 이미 있으면 재사용(중복 생성 안 함)")
    void moveToExistingTargetWeekReuses() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID existingTarget = insertWeeklyPlan(MAIN, NEXT_WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END);

        mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-08-10T00:00:00Z\",\"endAt\":\"2026-08-10T03:00:00Z\","
                                + "\"targetWeekStartDate\":\"2026-08-10\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plans WHERE user_id = ? AND week_start_date = ?",
                Integer.class, MAIN, NEXT_WEEK)).isEqualTo(1); // 중복 생성 없음
        assertThat(UUID.fromString(jdbc.queryForObject(
                "SELECT weekly_plan_id FROM plan_blocks WHERE plan_block_id = ?", String.class, blockId)))
                .isEqualTo(existingTarget);
    }

    @Test
    @DisplayName("동시 주차 이동 — 같은 신규 주로 블록 2개 병렬 이동, 500 없이 계획 1행으로 수렴 (UNIQUE 경합 방어)")
    void concurrentMoveToSameNewWeekConvergesWithout500() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task1 = insertTask(project, "태스크1", TaskStatus.UNASSIGNED);
        UUID task2 = insertTask(project, "태스크2", TaskStatus.UNASSIGNED);
        UUID block1 = placeTaskBlock(plan, task1, START, END);
        UUID block2 = placeTaskBlock(plan, task2, START, END);
        // NEXT_WEEK 계획은 아직 없음 → 두 요청이 각자 get-or-create 시도 = UNIQUE 경합 유발

        String body = "{\"startAt\":\"2026-08-10T00:00:00Z\",\"endAt\":\"2026-08-10T01:00:00Z\","
                + "\"targetWeekStartDate\":\"2026-08-10\"}";

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            // 두 스레드를 go 신호에 맞춰 같은 순간 출발시켜 경합 창을 넓힌다.
            java.util.concurrent.Future<Integer> f1 = pool.submit(() -> moveStatus(block1, body, ready, go));
            java.util.concurrent.Future<Integer> f2 = pool.submit(() -> moveStatus(block2, body, ready, go));
            ready.await();
            go.countDown();
            int s1 = f1.get();
            int s2 = f2.get();

            // 어느 쪽도 500이 아니어야 한다(경합이 터졌다면 catch 로 기존 반환에 수렴).
            assertThat(s1).isNotEqualTo(500);
            assertThat(s2).isNotEqualTo(500);
            assertThat(s1).isEqualTo(200);
            assertThat(s2).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        // 대상 주 계획은 정확히 1행(중복 생성 없음)이고 블록 2개가 그쪽으로 이동.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plans WHERE user_id = ? AND week_start_date = ?",
                Integer.class, MAIN, NEXT_WEEK)).isEqualTo(1);
        UUID target = UUID.fromString(jdbc.queryForObject(
                "SELECT weekly_plan_id FROM weekly_plans WHERE user_id = ? AND week_start_date = ?",
                String.class, MAIN, NEXT_WEEK));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = ?",
                Integer.class, target)).isEqualTo(2);
    }

    /** 두 스레드가 go 신호에 맞춰 동시에 PATCH-이동하고 HTTP 상태를 돌려준다. */
    private int moveStatus(UUID blockId, String body, java.util.concurrent.CountDownLatch ready,
                           java.util.concurrent.CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    // ---------- 검증 ----------

    @Test
    @DisplayName("시작 >= 종료 → 422 E-PLAN-002")
    void startNotBeforeEnd() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END);

        mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-08-03T03:00:00Z\",\"endAt\":\"2026-08-03T02:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PLAN-002"));
    }

    @Test
    @DisplayName("5분 단위 아님 → 422 E-COM-009 (field=startAt)")
    void notFiveMinuteAligned() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK, "DRAFT", null);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        UUID blockId = placeTaskBlock(plan, task, START, END);

        mockMvc.perform(patch("/api/v1/plan-blocks/" + blockId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-08-03T00:02:00Z\",\"endAt\":\"2026-08-03T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("startAt"));
    }

    // ---------- 소유 스코프 ----------

    @Test
    @DisplayName("없는 블록 → 404")
    void moveUnknownBlock() throws Exception {
        mockMvc.perform(patch("/api/v1/plan-blocks/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"" + START + "\",\"endAt\":\"" + END + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 블록 → 404 (존재 은닉)")
    void moveOtherUserBlockHidden() throws Exception {
        UUID othersPlan = insertWeeklyPlan(OTHER, WEEK, "DRAFT", null);
        UUID othersProject = insertProject(OTHER, "타인프로젝트");
        UUID othersTask = insertTask(othersProject, "타인태스크", TaskStatus.UNASSIGNED);
        UUID othersBlock = placeTaskBlock(OTHER, othersPlan, othersTask, START, END);

        mockMvc.perform(patch("/api/v1/plan-blocks/" + othersBlock).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-08-03T01:00:00Z\",\"endAt\":\"2026-08-03T02:00:00Z\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- fixtures ----------

    private UUID placeTaskBlock(UUID plan, UUID task, String start, String end) throws Exception {
        return placeTaskBlock(MAIN, plan, task, start, end);
    }

    private UUID placeTaskBlock(UUID userId, UUID plan, UUID task, String start, String end) throws Exception {
        String body = "{\"blockType\":\"TASK\",\"taskId\":\"" + task + "\","
                + "\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\"}";
        ResultActions actions = mockMvc.perform(post("/api/v1/weekly-plans/" + plan + "/blocks")
                .header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        return UUID.fromString(JsonPath.read(actions.andReturn().getResponse().getContentAsString(),
                "$.data.planBlockId"));
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
