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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재계획 대안 적용 API 통합 테스트 (PLAN-29) — {@code POST /replan-options/{optionId}/application}.
 * 선택 대안대로 블록 이동·is_selected 기록·WeeklyPlanView 반환·확정 아님(DRAFT)·404를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ReplanApplyApiTest {

    private static final UUID MAIN = UUID.fromString("eeee2222-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeee2222-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3); // 월요일
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID project;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM replan_options WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM availability_patterns WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        project = insertProject(MAIN, "프로젝트");
        insertAvailability(MAIN, "MON", "09:00", "18:00");
    }

    @Test
    @DisplayName("대안 적용 → 200 · 겹쳤던 블록이 대안대로 이동 · is_selected 기록 · DRAFT 유지")
    void applyMovesBlocksAndRecordsSelection() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID t1 = insertTask(project, "태스크1", 1, 60);
        UUID t2 = insertTask(project, "태스크2", 2, 60);
        insertTaskBlock(plan, t1, at(9, 0), at(10, 0));
        UUID block2 = insertTaskBlock(plan, t2, at(9, 30), at(10, 30)); // 겹침

        // 대안 생성 → MINIMAL_CHANGE의 optionId 확보
        String genJson = mockMvc.perform(post(gen(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String optionId = JsonPath.read(genJson, "$.data.options[0].replanOptionId"); // MINIMAL_CHANGE

        mockMvc.perform(post(apply(optionId)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.weeklyPlanId").value(plan.toString()))
                .andExpect(jsonPath("$.data.plan.status").value("DRAFT")) // 확정 아님
                .andExpect(jsonPath("$.data.blocks.length()").value(2));

        // 겹쳤던 block2가 KST 10:00(09:30에서 이동)으로 옮겨짐 (MINIMAL_CHANGE 결과).
        // timestamptz를 Instant로 받아 시각 비교(문자열 포맷은 세션 타임존 영향).
        Instant movedStart = jdbc.queryForObject(
                "SELECT start_at FROM plan_blocks WHERE plan_block_id = ?", Instant.class, block2);
        assertThat(movedStart).isEqualTo(at(10, 0)); // KST 10:00 = UTC 01:00

        // is_selected 기록: 선택된 대안 1개
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM replan_options WHERE weekly_plan_id = ? AND is_selected = true",
                Integer.class, plan)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_selected FROM replan_options WHERE replan_option_id = ?",
                Boolean.class, UUID.fromString(optionId))).isTrue();
    }

    @Test
    @DisplayName("다른 대안 재적용 → 선택은 계획당 하나(이전 선택 해제)")
    void reapplyKeepsSingleSelection() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID t1 = insertTask(project, "태스크1", 1, 60);
        insertTaskBlock(plan, t1, at(9, 0), at(10, 0));

        String genJson = mockMvc.perform(post(gen(plan)).header("X-Dev-User", MAIN.toString()))
                .andReturn().getResponse().getContentAsString();
        String opt0 = JsonPath.read(genJson, "$.data.options[0].replanOptionId");
        String opt1 = JsonPath.read(genJson, "$.data.options[1].replanOptionId");

        mockMvc.perform(post(apply(opt0)).header("X-Dev-User", MAIN.toString())).andExpect(status().isOk());
        mockMvc.perform(post(apply(opt1)).header("X-Dev-User", MAIN.toString())).andExpect(status().isOk());

        // 선택은 여전히 1개(opt1만)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM replan_options WHERE weekly_plan_id = ? AND is_selected = true",
                Integer.class, plan)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_selected FROM replan_options WHERE replan_option_id = ?",
                Boolean.class, UUID.fromString(opt1))).isTrue();
    }

    @Test
    @DisplayName("없는/타인 대안 → 404")
    void applyNotFound() throws Exception {
        mockMvc.perform(post(apply(UUID.randomUUID().toString())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private String gen(UUID planId) {
        return "/api/v1/weekly-plans/" + planId + "/replan-options";
    }

    private String apply(String optionId) {
        return "/api/v1/replan-options/" + optionId + "/application";
    }

    private Instant at(int hour, int minute) {
        return WEEK.atTime(hour, minute).atZone(KST).toInstant();
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

    private void insertAvailability(UUID userId, String weekday, String start, String end) {
        jdbc.update("""
                INSERT INTO availability_patterns (availability_pattern_id, user_id, weekday, start_time, end_time, is_active)
                VALUES (?, ?, ?, ?, ?, true)
                """, UUID.randomUUID(), userId, weekday, LocalTime.parse(start), LocalTime.parse(end));
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

    private UUID insertTask(UUID projectId, String title, Integer priority, Integer estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, ?, ?, NULL, ?, 0, ?)
                """, id, projectId, title, estimatedMinutes, priority, TaskStatus.IN_PROGRESS.name(),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTaskBlock(UUID planId, UUID taskId, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, ?, NULL, 'TASK', ?, ?, 'SCHEDULED', ?)
                """, id, planId, taskId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', NULL, 0, ?)
                """, id, userId, weekStart, weekStart.plusDays(6), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
