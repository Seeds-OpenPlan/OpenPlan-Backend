package com.openplan.backend.weeklyplan.controller;

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
 * 자동 배치 제안 API 통합 테스트 (RB-PLAN-01 / SS-05) — {@code POST /weekly-plans/{planId}/auto-placements}.
 * first-fit 제안·저장 안 함·미배치 전량 대상·404를 고정한다. 알고리즘 세부는 엔진 단위테스트가 담당.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class AutoPlacementApiTest {

    private static final UUID MAIN = UUID.fromString("cccc2222-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccc2222-0000-0000-0000-000000000002");
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
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM availability_patterns WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        project = insertProject(MAIN, "프로젝트");
        insertAvailability(MAIN, "MON", "09:00", "12:00"); // 180분 가용
    }

    @Test
    @DisplayName("미배치 전량 자동 배치 → 200 · 제안 반환 · 저장 안 함(plan_blocks 0)")
    void proposesUnassigned() throws Exception {
        UUID t1 = insertTask(project, "태스크1", 1, 60, TaskStatus.UNASSIGNED);
        UUID t2 = insertTask(project, "태스크2", 2, 60, TaskStatus.UNASSIGNED);
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedBlocks.length()").value(2))
                .andExpect(jsonPath("$.data.proposedBlocks[0].blockType").value("TASK"))
                .andExpect(jsonPath("$.data.proposedBlocks[0].taskId").value(t1.toString())) // 우선순위 1 먼저
                .andExpect(jsonPath("$.data.proposedBlocks[0].startAt").exists())
                .andExpect(jsonPath("$.data.unplacedTaskIds.length()").value(0))
                .andExpect(jsonPath("$.data.reason").exists());

        // 저장 안 함 (C-2)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = ?",
                Integer.class, plan)).isZero();
        // 태스크 상태도 그대로 UNASSIGNED
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, t1))
                .isEqualTo("UNASSIGNED");
    }

    @Test
    @DisplayName("가용 초과분은 unplaced — 180분 가용에 120+120이면 하나만 배치")
    void reportsUnplaced() throws Exception {
        insertTask(project, "큰태스크1", 1, 120, TaskStatus.UNASSIGNED);
        insertTask(project, "큰태스크2", 2, 120, TaskStatus.UNASSIGNED);
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedBlocks.length()").value(1))
                .andExpect(jsonPath("$.data.unplacedTaskIds.length()").value(1));
    }

    @Test
    @DisplayName("taskIds 지정 → 그 태스크만 대상")
    void placesOnlyRequested() throws Exception {
        UUID t1 = insertTask(project, "태스크1", 1, 60, TaskStatus.UNASSIGNED);
        insertTask(project, "태스크2", 2, 60, TaskStatus.UNASSIGNED);
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskIds\":[\"" + t1 + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedBlocks.length()").value(1))
                .andExpect(jsonPath("$.data.proposedBlocks[0].taskId").value(t1.toString()));
    }

    @Test
    @DisplayName("미배치 없음 → 빈 제안")
    void emptyWhenNoUnassigned() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedBlocks.length()").value(0))
                .andExpect(jsonPath("$.data.unplacedTaskIds.length()").value(0));
    }

    @Test
    @DisplayName("없는/타인 계획 → 404")
    void planNotFound() throws Exception {
        mockMvc.perform(post(PATH(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private String PATH(UUID planId) {
        return "/api/v1/weekly-plans/" + planId + "/auto-placements";
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

    private UUID insertTask(UUID projectId, String title, Integer priority, Integer estimatedMinutes, TaskStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, ?, ?, NULL, ?, 0, ?)
                """, id, projectId, title, estimatedMinutes, priority, status.name(),
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
