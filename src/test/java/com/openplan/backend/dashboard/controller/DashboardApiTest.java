package com.openplan.backend.dashboard.controller;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대시보드 조립 통합 테스트 (ST-B2-15). {@link FixedClockConfig#FIXED_TODAY} = 2026-07-15(수, KST) ·
 * 주(월요일 시작) = 07-13(월)~07-19(일) · {@link FixedClockConfig#FIXED_NOW} = 2026-07-15T00:00:00Z(UTC)
 * = KST 09:00.
 *
 * <p>고정하는 것: 데이터 0건일 때 빈 상태 shape · priorityAction 전순서 중 PLACE_UNASSIGNED/
 * REPLACE_TODAY_INCOMPLETE 판정 · riskIssues count=0 유형 제외 · weeklyImpactProjects HAS_UNASSIGNED 배지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class DashboardApiTest {

    private static final String PATH = "/api/v1/dashboard";
    private static final UUID MAIN = UUID.fromString("eeee0003-0000-0000-0000-000000000001");
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 13);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        jdbc.update("DELETE FROM execution_logs WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id = ?)", MAIN);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM tasks WHERE project_id IN (SELECT project_id FROM projects WHERE user_id = ?)", MAIN);
        jdbc.update("DELETE FROM projects WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM availability_patterns WHERE user_id = ?", MAIN);
    }

    @Test
    @DisplayName("데이터 0건 → 200 · 빈 상태 shape(모든 카운트 0, priorityAction=null, 목록 전부 빈 배열)")
    void emptyDashboard() throws Exception {
        mockMvc.perform(dashboardGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusBoard.plannedMinutes").value(0))
                .andExpect(jsonPath("$.data.statusBoard.availableMinutes").value(0))
                .andExpect(jsonPath("$.data.statusBoard.deltaMinutes").value(0))
                .andExpect(jsonPath("$.data.priorityAction").isEmpty())
                .andExpect(jsonPath("$.data.riskIssues").isArray())
                .andExpect(jsonPath("$.data.riskIssues").isEmpty())
                .andExpect(jsonPath("$.data.todayBoard.items").isEmpty())
                .andExpect(jsonPath("$.data.todayBoard.remainingMinutes").value(0))
                .andExpect(jsonPath("$.data.weeklyImpactProjects").isEmpty())
                .andExpect(jsonPath("$.data.busyWeekdays").isEmpty());
    }

    @Test
    @DisplayName("미배치 태스크 존재 → priorityAction=PLACE_UNASSIGNED · riskIssues에 UNASSIGNED_TASKS · 영향 프로젝트에 HAS_UNASSIGNED")
    void unassignedTaskDrivesTopPriority() throws Exception {
        UUID project = insertProject(MAIN, "미배치 프로젝트", null);
        insertTask(project, "UNASSIGNED");

        mockMvc.perform(dashboardGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priorityAction.actionType").value("PLACE_UNASSIGNED"))
                .andExpect(jsonPath("$.data.riskIssues[0].riskType").value("UNASSIGNED_TASKS"))
                .andExpect(jsonPath("$.data.riskIssues[0].count").value(1))
                .andExpect(jsonPath("$.data.weeklyImpactProjects[0].projectId").value(project.toString()))
                .andExpect(jsonPath("$.data.weeklyImpactProjects[0].impactBadges[0]").value("HAS_UNASSIGNED"));
    }

    @Test
    @DisplayName("겹침(V1)만 있는 계획 → priorityAction=RESOLVE_OVERLAP (미배치보다 앞 — 차단류가 우선)")
    void overlapIssueDrivesTopPriorityAheadOfUnassigned() throws Exception {
        // 미배치 태스크(3위 후보)를 일부러 함께 둬서, 2위인 겹침이 그보다 앞서는지까지 고정한다.
        UUID project = insertProject(MAIN, "겹침 프로젝트", null);
        insertTask(project, "UNASSIGNED");
        UUID plan = insertWeeklyPlan(MAIN, WEEK_START);
        insertValidationIssue(plan, "V1_OVERLAP", "BLOCK");

        mockMvc.perform(dashboardGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priorityAction.actionType").value("RESOLVE_OVERLAP"))
                .andExpect(jsonPath("$.data.priorityAction.routePath").value("SCR-PLAN 검토 패널"));
    }

    @Test
    @DisplayName("고정 일정 충돌(V2)이 함께 있으면 겹침(V1)보다 앞선다 — 확정문 전순서 1위 > 2위")
    void fixedConflictOutranksOverlap() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_START);
        insertValidationIssue(plan, "V1_OVERLAP", "BLOCK");
        insertValidationIssue(plan, "V2_FIXED_CONFLICT", "BLOCK");

        mockMvc.perform(dashboardGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priorityAction.actionType").value("RESOLVE_FIXED_CONFLICT"));
    }

    @Test
    @DisplayName("오늘 종료 시각이 지난 미완료 TASK 블록 → priorityAction=REPLACE_TODAY_INCOMPLETE(미배치 없을 때)")
    void todayIncompleteBlockDrivesPriority() throws Exception {
        UUID project = insertProject(MAIN, "진행 프로젝트", null);
        UUID task = insertTask(project, "IN_PROGRESS"); // UNASSIGNED가 아니라 우선순위 3위가 걸리지 않는다
        UUID weeklyPlan = insertWeeklyPlan(MAIN, WEEK_START);
        // KST 07-15 08:00~08:30 — referenceTime(KST 09:00)보다 앞서 끝났고 아직 SCHEDULED(미완료).
        insertPlanBlock(weeklyPlan, task, "2026-07-14T23:00:00Z", "2026-07-14T23:30:00Z", "SCHEDULED");

        mockMvc.perform(dashboardGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priorityAction.actionType").value("REPLACE_TODAY_INCOMPLETE"))
                .andExpect(jsonPath("$.data.todayBoard.items[0].completed").value(false))
                .andExpect(jsonPath("$.data.todayBoard.items[0].taskId").value(task.toString()));
    }

    @Test
    @DisplayName("완료된 오늘 블록만 있으면 REPLACE_TODAY_INCOMPLETE가 뜨지 않는다(긍정 상태)")
    void completedTodayBlockDoesNotTriggerPriority() throws Exception {
        UUID project = insertProject(MAIN, "진행 프로젝트", null);
        UUID task = insertTask(project, "IN_PROGRESS");
        UUID weeklyPlan = insertWeeklyPlan(MAIN, WEEK_START);
        insertPlanBlock(weeklyPlan, task, "2026-07-14T23:00:00Z", "2026-07-14T23:30:00Z", "COMPLETED");

        mockMvc.perform(dashboardGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priorityAction").isEmpty())
                .andExpect(jsonPath("$.data.todayBoard.items[0].completed").value(true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder dashboardGet() {
        return get(PATH).header("X-Dev-User", MAIN.toString());
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

    private UUID insertProject(UUID userId, String name, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, ?, 'IN_PROGRESS', NULL, NULL, 0, ?)
                """, id, userId, name, dueDate, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, '테스트 태스크', NULL, 30, NULL, NULL, ?, 0, ?)
                """, id, projectId, status, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', NULL, 0, ?)
                """, id, userId, weekStart, weekStart.plusDays(6), OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertValidationIssue(UUID weeklyPlanId, String issueType, String severity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO validation_issues (validation_issue_id, weekly_plan_id, plan_block_id, task_id,
                                               wbs_item_id, issue_type, severity, message,
                                               resolution_status, created_at)
                VALUES (?, ?, NULL, NULL, NULL, ?, ?, '테스트 이슈', 'OPEN', ?)
                """, id, weeklyPlanId, issueType, severity, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertPlanBlock(UUID weeklyPlanId, UUID taskId, String startAt, String endAt, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id,
                                         block_type, start_at, end_at, status, created_at)
                VALUES (?, ?, ?, NULL, 'TASK', ?, ?, ?, ?)
                """, id, weeklyPlanId, taskId,
                OffsetDateTime.parse(startAt), OffsetDateTime.parse(endAt), status,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }
}
