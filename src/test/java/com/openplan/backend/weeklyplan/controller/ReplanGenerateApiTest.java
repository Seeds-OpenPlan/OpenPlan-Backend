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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재계획 대안 생성 API 통합 테스트 (SS-07~09) — {@code POST /weekly-plans/{planId}/replan-options}.
 * 3전략+기준선 반환·JSONB(proposed_blocks) 영속·전면 교체·404를 고정한다. 알고리즘 세부는 엔진 단위테스트가 담당.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ReplanGenerateApiTest {

    private static final UUID MAIN = UUID.fromString("dddd2222-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("dddd2222-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3); // 월요일

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
    @DisplayName("대안 생성 → 201 · baseline(KEEP_CURRENT) + 3전략 · JSONB 영속(3행)")
    void generatesThreeStrategiesAndPersists() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID t1 = insertTask(project, "태스크1", 1, 60);
        UUID t2 = insertTask(project, "태스크2", 2, 60);
        insertTaskBlock(plan, t1, at(9, 0), at(10, 0));
        insertTaskBlock(plan, t2, at(9, 30), at(10, 30)); // 겹침

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.baseline.strategyType").value("KEEP_CURRENT"))
                .andExpect(jsonPath("$.data.baseline.replanOptionId").doesNotExist()) // 기준선은 저장 안 함
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.options[0].strategyType").value("MINIMAL_CHANGE"))
                .andExpect(jsonPath("$.data.options[0].replanOptionId").exists())
                .andExpect(jsonPath("$.data.options[0].proposedBlocks[0].blockType").value("TASK"))
                .andExpect(jsonPath("$.data.options[1].strategyType").value("DEADLINE_FIRST"))
                .andExpect(jsonPath("$.data.options[2].strategyType").value("WORKLOAD_BALANCE"));

        // JSONB 영속 확인: 3행 + proposed_blocks가 jsonb로 저장됨
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replan_options WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(3);
        // jsonb 컬럼이 배열로 저장됐는지(jsonb_array_length로 검증 — 텍스트가 아니라 진짜 jsonb)
        Integer len = jdbc.queryForObject(
                "SELECT jsonb_array_length(proposed_blocks) FROM replan_options "
                        + "WHERE weekly_plan_id = ? AND strategy_type = 'DEADLINE_FIRST'", Integer.class, plan);
        assertThat(len).isEqualTo(2); // 태스크 2개 재배치
    }

    @Test
    @DisplayName("재생성 → 기존 대안 전면 교체(중복 누적 없음, 3행 유지)")
    void regenerateReplacesPrevious() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID t1 = insertTask(project, "태스크1", 1, 60);
        insertTaskBlock(plan, t1, at(9, 0), at(10, 0));

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString())).andExpect(status().isCreated());
        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString())).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replan_options WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(3); // 6이 아님 — 전면 교체
    }

    @Test
    @DisplayName("블록 없는 계획 → 201 · 대안 3개(빈 proposedBlocks)")
    void emptyPlanStillReturnsStrategies() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.options[0].proposedBlocks.length()").value(0));
    }

    @Test
    @DisplayName("없는/타인 계획 → 404")
    void planNotFound() throws Exception {
        mockMvc.perform(post(PATH(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- GET 재조회 ----------

    @Test
    @DisplayName("GET 재조회 → 저장된 대안 3개(JSONB 역직렬화) · 기준선 없음")
    void listReturnsStoredOptions() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID t1 = insertTask(project, "태스크1", 1, 60);
        UUID t2 = insertTask(project, "태스크2", 2, 60);
        insertTaskBlock(plan, t1, at(9, 0), at(10, 0));
        insertTaskBlock(plan, t2, at(9, 30), at(10, 30));
        // 먼저 생성해 저장
        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString())).andExpect(status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3)) // 배열(baseline 래퍼 없음)
                .andExpect(jsonPath("$.data[0].strategyType").value("MINIMAL_CHANGE"))
                .andExpect(jsonPath("$.data[0].replanOptionId").exists())
                // JSONB proposed_blocks가 제대로 역직렬화됐는지 — 시각까지 복원
                .andExpect(jsonPath("$.data[0].proposedBlocks[0].blockType").value("TASK"))
                .andExpect(jsonPath("$.data[0].proposedBlocks[0].startAt").exists())
                .andExpect(jsonPath("$.data[0].proposedBlocks[1].startAt").value("2026-08-03T01:00:00Z"));
    }

    @Test
    @DisplayName("GET — 생성 전이면 빈 목록")
    void listEmptyBeforeGenerate() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET — 없는/타인 계획 → 404")
    void listPlanNotFound() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(PATH(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private String PATH(UUID planId) {
        return "/api/v1/weekly-plans/" + planId + "/replan-options";
    }

    private Instant at(int hour, int minute) {
        return WEEK.atTime(hour, minute).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
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

    private void insertTaskBlock(UUID planId, UUID taskId, Instant start, Instant end) {
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, ?, NULL, 'TASK', ?, ?, 'SCHEDULED', ?)
                """, UUID.randomUUID(), planId, taskId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
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
