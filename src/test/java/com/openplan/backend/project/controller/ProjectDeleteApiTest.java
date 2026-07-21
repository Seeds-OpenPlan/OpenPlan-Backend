package com.openplan.backend.project.controller;

import com.openplan.backend.project.entity.ProjectStatus;
import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 삭제 API 통합 테스트 (PROJ-09 / EP-6). hard delete·FK cascade(B-3)·weekly_plans 캐시 재계산(B-4)·
 * 삭제 후 flush 순서(C1)를 실측 검증. project→task→weekly_plan→plan_block 체인을 JdbcTemplate로 직접 시딩.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectDeleteApiTest {

    private static final String PATH = "/api/v1/projects";
    private static final UUID MAIN = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant BLOCK_START = Instant.parse("2026-07-15T09:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("AC-09-1 · AC-09-6 정상 삭제 → 204 · 태스크·블록 cascade 제거 · 주차 캐시 90→0")
    void deleteCascadesAndRecalculates() throws Exception {
        UUID project = seedProject(MAIN, ProjectStatus.IN_PROGRESS);
        UUID task = seedTask(project);
        UUID weeklyPlan = seedWeeklyPlan(MAIN, "2026-07-13", 90);
        seedTaskBlock(weeklyPlan, task, 90);

        mockMvc.perform(delete(PATH + "/" + project).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        // 프로젝트·태스크·블록 제거
        mockMvc.perform(get(PATH + "/" + project).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound());
        assertThat(count("tasks", "task_id", task)).isZero();
        assertThat(count("plan_blocks", "weekly_plan_id", weeklyPlan)).isZero();
        // 캐시 재계산: 90 → 0 (남은 블록 없음)
        assertThat(totalMinutes(weeklyPlan)).isZero();
    }

    @Test
    @DisplayName("B-4·C1 재계산은 '남은 블록' 기준 — 태스크블록(90)만 사라지고 일정블록(60) 유지 → 150→60")
    void recalculateUsesRemainingBlocks() throws Exception {
        UUID project = seedProject(MAIN, ProjectStatus.IN_PROGRESS);
        UUID task = seedTask(project);
        UUID weeklyPlan = seedWeeklyPlan(MAIN, "2026-07-20", 150);
        seedTaskBlock(weeklyPlan, task, 90);              // 프로젝트 소속 — 삭제로 사라짐
        UUID schedule = seedSchedule(MAIN);
        seedScheduleBlock(weeklyPlan, schedule, 60);      // 사용자 일정 — 삭제와 무관, 유지

        mockMvc.perform(delete(PATH + "/" + project).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        // flush(C1) 없으면 삭제 전 태스크블록까지 합산해 150이 됨 → 60이어야 정상
        assertThat(totalMinutes(weeklyPlan)).isEqualTo(60);
    }

    @Test
    @DisplayName("AC-09-3 타인 소유 삭제 → 404, 아무것도 삭제되지 않음")
    void deleteOtherOwner() throws Exception {
        UUID others = seedProject(OTHER, ProjectStatus.IN_PROGRESS);
        mockMvc.perform(delete(PATH + "/" + others).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
        assertThat(count("projects", "project_id", others)).isEqualTo(1); // 그대로
    }

    @Test
    @DisplayName("AC-09-4 상태 무관 삭제 — IN_PROGRESS·PAUSED·CLOSED 모두 204")
    void deleteAnyStatus() throws Exception {
        for (ProjectStatus st : ProjectStatus.values()) {
            UUID id = seedProject(MAIN, st);
            mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("AC-09-5 재삭제 → 두 번째는 404")
    void reDelete() throws Exception {
        UUID id = seedProject(MAIN, ProjectStatus.IN_PROGRESS);
        mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private Integer totalMinutes(UUID weeklyPlanId) {
        return jdbc.queryForObject(
                "SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?", Integer.class, weeklyPlanId);
    }

    private int count(String table, String col, UUID id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?", Integer.class, id);
        return n == null ? 0 : n;
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

    private UUID seedProject(UUID userId, ProjectStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, status, closed_at, version, created_at)
                VALUES (?, ?, '삭제대상', ?, ?, 0, ?)
                """, id, userId, status.name(),
                status == ProjectStatus.CLOSED ? OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC) : null,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID seedTask(UUID projectId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (task_id, project_id, title) VALUES (?, ?, '태스크')", id, projectId);
        return id;
    }

    private UUID seedWeeklyPlan(UUID userId, String weekStart, int total) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date, total_planned_minutes)
                VALUES (?, ?, ?::date, (?::date + INTERVAL '6 days'), ?)
                """, id, userId, weekStart, weekStart, total);
        return id;
    }

    private void seedTaskBlock(UUID weeklyPlanId, UUID taskId, int minutes) {
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, block_type, start_at, end_at)
                VALUES (?, ?, ?, 'TASK', ?, ?)
                """, UUID.randomUUID(), weeklyPlanId, taskId,
                OffsetDateTime.ofInstant(BLOCK_START, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BLOCK_START.plusSeconds(minutes * 60L), ZoneOffset.UTC));
    }

    private UUID seedSchedule(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (schedule_id, user_id, title, start_at, end_at)
                VALUES (?, ?, '개인일정', ?, ?)
                """, id, userId,
                OffsetDateTime.ofInstant(BLOCK_START, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BLOCK_START.plusSeconds(3600), ZoneOffset.UTC));
        return id;
    }

    private void seedScheduleBlock(UUID weeklyPlanId, UUID scheduleId, int minutes) {
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, schedule_id, block_type, start_at, end_at)
                VALUES (?, ?, ?, 'SCHEDULE', ?, ?)
                """, UUID.randomUUID(), weeklyPlanId, scheduleId,
                OffsetDateTime.ofInstant(BLOCK_START, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BLOCK_START.plusSeconds(minutes * 60L), ZoneOffset.UTC));
    }
}
