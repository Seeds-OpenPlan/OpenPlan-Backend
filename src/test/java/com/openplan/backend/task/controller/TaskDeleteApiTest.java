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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 태스크 삭제 API 통합 테스트 (TUT-07 / EP-6 · AC-D-1~4). 지명 테스트 [T1](삭제 후 주간계획 캐시 값 단언 —
 * flush 누락 결함을 값으로 잡는다)을 포함한다. cascade는 DB FK 위임(TB-3), 재계산은 TB-4 동일 tx.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskDeleteApiTest {

    private static final UUID MAIN = UUID.fromString("b2b2b2b2-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("b2b2b2b2-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID inProgress;
    private UUID closed;

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
    }

    // ---------- AC-D-2 [T1] 값 단언 ----------

    @Test
    @DisplayName("AC-D-2 [T1] 블록 90분 배치 태스크 삭제 → 같은 tx에서 주간계획 total 90→0 값 단언 + 블록 cascade 제거")
    void deleteRecalculatesWeeklyTotal() throws Exception {
        UUID wp = insertWeeklyPlan(MAIN, 90); // total_planned_minutes 사전값 90
        UUID task = insertTask(inProgress, "90분태스크", TaskStatus.IN_PROGRESS);
        insertBlock(wp, task, BASE, BASE.plusSeconds(5400), "SCHEDULED"); // 90분

        // 사전 단언
        Integer before = jdbc.queryForObject(
                "SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?", Integer.class, wp);
        assertThat(before).isEqualTo(90);

        del(task).andExpect(status().isNoContent());

        // 사후 값 단언 — flush 누락 결함이면 삭제 전 블록을 합산해 90이 남는다(무예외). 0이어야 함.
        Integer after = jdbc.queryForObject(
                "SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?", Integer.class, wp);
        assertThat(after).isZero();

        // cascade — 태스크·블록 제거
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE task_id = ?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE task_id = ?", Integer.class, task)).isZero();
    }

    // ---------- AC-D-1 · AC-D-3 정상 삭제 ----------

    @Test
    @DisplayName("AC-D-1·D-3 version 불요 → 204 · 이후 조회 404")
    void deleteOk() throws Exception {
        UUID task = insertTask(inProgress, "삭제대상", TaskStatus.UNASSIGNED);

        del(task).andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/tasks/" + task).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound());
    }

    // ---------- AC-D-4 스코프·멱등·상태무관·CLOSED ----------

    @Test
    @DisplayName("AC-D-4 부재·타인 → 404 · 재삭제 → 404(멱등)")
    void notFoundAndReDelete() throws Exception {
        del(UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID othersProject = insertProject(OTHER, "타인", ProjectStatus.IN_PROGRESS, null);
        UUID othersTask = insertTask(othersProject, "타인태스크", TaskStatus.UNASSIGNED);
        del(othersTask).andExpect(status().isNotFound());
        // 타인 것은 삭제 안 됨
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE task_id = ?", Integer.class, othersTask))
                .isEqualTo(1);

        UUID mine = insertTask(inProgress, "내태스크", TaskStatus.UNASSIGNED);
        del(mine).andExpect(status().isNoContent());
        del(mine).andExpect(status().isNotFound()); // 재삭제 → 404
    }

    @Test
    @DisplayName("AC-D-4 태스크 상태 무관 삭제 — COMPLETED도 204")
    void deleteRegardlessOfTaskStatus() throws Exception {
        UUID completed = insertTask(inProgress, "완료태스크", TaskStatus.COMPLETED);
        del(completed).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("AC-D-4 CLOSED 프로젝트 하위 태스크 삭제 → 422 E-PROJ-005 (D-10) · 미삭제")
    void closedProjectGuard() throws Exception {
        UUID task = insertTask(closed, "종료프로젝트 태스크", TaskStatus.UNASSIGNED);

        del(task).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("project.status"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE task_id = ?", Integer.class, task))
                .isEqualTo(1); // 미삭제
    }

    // ---------- fixtures ----------

    private ResultActions del(UUID taskId) throws Exception {
        return mockMvc.perform(delete("/api/v1/tasks/" + taskId).header("X-Dev-User", MAIN.toString()));
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

    private UUID insertWeeklyPlan(UUID userId, int totalPlannedMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 0, ?)
                """,
                id, userId, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19),
                totalPlannedMinutes, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
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

    private void insertBlock(UUID weeklyPlanId, UUID taskId, Instant startAt, Instant endAt, String status) {
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, ?, NULL, 'TASK', ?, ?, ?, ?)
                """,
                UUID.randomUUID(), weeklyPlanId, taskId,
                OffsetDateTime.ofInstant(startAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(endAt, ZoneOffset.UTC),
                status, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
