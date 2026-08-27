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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WBS 뷰 API 통합 테스트 (PROJ-13 / GET {@code /projects/{projectId}/wbs}).
 *
 * <p>고정하는 계약: 태스크 제목 조인 · <b>정렬 startDate ASC, endDate ASC, wbsItemId ASC 서버 고정</b>
 * (간트 바를 시작일 순으로 — User 확정) · 기간 미설정 태스크 미포함 · 프로젝트 스코프 격리 ·
 * CLOSED/PAUSED도 조회 가능(자동종료 평가 없음) · 부재·타인 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectWbsApiTest {

    private static final UUID MAIN = UUID.fromString("ffffffff-0000-0000-0000-000000000005");
    private static final UUID OTHER = UUID.fromString("ffffffff-0000-0000-0000-000000000006");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM wbs_items WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("WBS 뷰 → 200 · 시작일 오름차순 · 태스크 제목 동봉 (생성 순서와 무관)")
    void listOrderedByStartDate() throws Exception {
        UUID project = insertProject(MAIN, "논문", ProjectStatus.IN_PROGRESS, null);
        // 생성 순서(자료조사→초안작성→검토)와 시작일 순서(초안작성→자료조사→검토)를 어긋나게 심는다
        UUID t1 = insertTask(project, "자료조사");
        UUID t2 = insertTask(project, "초안작성");
        UUID t3 = insertTask(project, "검토");
        insertWbs(project, t1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14));
        insertWbs(project, t2, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));
        insertWbs(project, t3, LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 20));

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].taskTitle").value("초안작성"))
                .andExpect(jsonPath("$.data[0].taskId").value(t2.toString()))
                .andExpect(jsonPath("$.data[0].wbsItemId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].startDate").value("2026-08-03"))
                .andExpect(jsonPath("$.data[0].endDate").value("2026-08-09"))
                .andExpect(jsonPath("$.data[1].taskTitle").value("자료조사"))
                .andExpect(jsonPath("$.data[1].startDate").value("2026-08-10"))
                .andExpect(jsonPath("$.data[2].taskTitle").value("검토"))
                .andExpect(jsonPath("$.data[2].startDate").value("2026-08-17"));
    }

    @Test
    @DisplayName("같은 시작일 → 종료일 오름차순(짧은 바 먼저)")
    void sameStartDateOrderedByEndDate() throws Exception {
        UUID project = insertProject(MAIN, "논문", ProjectStatus.IN_PROGRESS, null);
        UUID longer = insertTask(project, "긴작업");
        UUID shorter = insertTask(project, "짧은작업");
        insertWbs(project, longer, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 20));
        insertWbs(project, shorter, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5));

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskTitle").value("짧은작업"))
                .andExpect(jsonPath("$.data[1].taskTitle").value("긴작업"));
    }

    @Test
    @DisplayName("기간 미설정 태스크는 미포함 (WBS 행이 있는 것만)")
    void tasksWithoutWbsExcluded() throws Exception {
        UUID project = insertProject(MAIN, "논문", ProjectStatus.IN_PROGRESS, null);
        UUID withWbs = insertTask(project, "기간있음");
        insertTask(project, "기간없음1");
        insertTask(project, "기간없음2");
        insertWbs(project, withWbs, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].taskTitle").value("기간있음"));
    }

    @Test
    @DisplayName("WBS 없는 프로젝트 → 200 빈 배열 (404 아님)")
    void emptyProjectReturnsEmptyArray() throws Exception {
        UUID project = insertProject(MAIN, "빈프로젝트", ProjectStatus.IN_PROGRESS, null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("다른 프로젝트의 WBS는 섞이지 않는다 (프로젝트 스코프 격리)")
    void scopedToProject() throws Exception {
        UUID mine = insertProject(MAIN, "이프로젝트", ProjectStatus.IN_PROGRESS, null);
        UUID sibling = insertProject(MAIN, "옆프로젝트", ProjectStatus.IN_PROGRESS, null);
        UUID mineTask = insertTask(mine, "내태스크");
        UUID siblingTask = insertTask(sibling, "옆태스크");
        insertWbs(mine, mineTask, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));
        insertWbs(sibling, siblingTask, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)); // 더 이른 시작일

        mockMvc.perform(get(path(mine)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].taskTitle").value("내태스크"));
    }

    @Test
    @DisplayName("CLOSED·PAUSED 프로젝트도 조회 가능 (평가 불요 — 목록 관례 승계)")
    void closedAndPausedProjectsReadable() throws Exception {
        UUID closed = insertProject(MAIN, "종료", ProjectStatus.CLOSED, BASE);
        UUID paused = insertProject(MAIN, "일시중지", ProjectStatus.PAUSED, null);
        UUID closedTask = insertTask(closed, "종료태스크");
        UUID pausedTask = insertTask(paused, "중지태스크");
        insertWbs(closed, closedTask, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));
        insertWbs(paused, pausedTask, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));

        mockMvc.perform(get(path(closed)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskTitle").value("종료태스크"));
        mockMvc.perform(get(path(paused)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskTitle").value("중지태스크"));
    }

    @Test
    @DisplayName("없는 프로젝트 → 404 E-COM-004")
    void notFound() throws Exception {
        mockMvc.perform(get(path(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 프로젝트 → 404 (존재 은닉 — 빈 배열 아님)")
    void otherUserHidden() throws Exception {
        UUID project = insertProject(OTHER, "남의프로젝트", ProjectStatus.IN_PROGRESS, null);
        UUID task = insertTask(project, "남의태스크");
        insertWbs(project, task, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/wbs";
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
                """, id, userId, name, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, 60, NULL, NULL, ?, 0, ?)
                """, id, projectId, title, TaskStatus.UNASSIGNED.name(),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertWbs(UUID projectId, UUID taskId, LocalDate startDate, LocalDate endDate) {
        jdbc.update("""
                INSERT INTO wbs_items (wbs_item_id, project_id, task_id, start_date, end_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, taskId, startDate, endDate,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
