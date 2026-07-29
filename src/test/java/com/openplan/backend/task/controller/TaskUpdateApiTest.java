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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 태스크 편집 API 통합 테스트 (PROJ-18=PLAN-10 / EP-4 · AC-E-1~5). 전체 폼 교체·낙관락·검사 순서
 * (404→422 CLOSED→409 version→422 필드→404 category)·COMPLETED 편집 허용을 고정한다.
 * FixedClockConfig(FIXED_TODAY=2026-07-15)로 평가 결정성 확보.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskUpdateApiTest {

    private static final UUID MAIN = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("ffffffff-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID inProgress;
    private UUID paused;
    private UUID closed;
    private UUID myCategory;
    private UUID othersCategory;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM task_categories WHERE user_id IN (?, ?)", MAIN, OTHER);

        inProgress = insertProject(MAIN, "진행중", ProjectStatus.IN_PROGRESS, null);
        paused = insertProject(MAIN, "일시중지", ProjectStatus.PAUSED, null);
        closed = insertProject(MAIN, "종료", ProjectStatus.CLOSED, BASE);
        myCategory = insertCategory(MAIN, "내 카테고리");
        othersCategory = insertCategory(OTHER, "타인 카테고리");
    }

    // ---------- AC-E-1 정상 편집 ----------

    @Test
    @DisplayName("AC-E-1 전 필드 제출 → 200 · 전부 반영 · version 증가 · categoryId null=해제")
    void updateOk() throws Exception {
        UUID id = insertTask(inProgress, myCategory, "원제목", "원메모", 30, 1,
                LocalDate.of(2099, 1, 1), TaskStatus.UNASSIGNED, 0);

        patch(id, """
                {"title":"수정제목","memo":"수정메모","estimatedMinutes":15,"priority":3,
                 "dueDate":"2099-12-31","categoryId":null,"version":0}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수정제목"))
                .andExpect(jsonPath("$.data.memo").value("수정메모"))
                .andExpect(jsonPath("$.data.estimatedMinutes").value(15))
                .andExpect(jsonPath("$.data.priority").value(3))
                .andExpect(jsonPath("$.data.dueDate").value("2099-12-31"))
                .andExpect(jsonPath("$.data.categoryId").doesNotExist())   // null=해제
                .andExpect(jsonPath("$.data.version").value(1))            // 증가 반영
                .andExpect(jsonPath("$.data.status").value("UNASSIGNED")); // 편집으로 불변
    }

    // ---------- AC-E-1 부분 수정 (true PATCH) ----------

    @Test
    @DisplayName("AC-E-1 부분 수정 — 제목만 보내면 memo·priority·dueDate·categoryId 유지")
    void partialUpdateKeepsUnsentFields() throws Exception {
        UUID id = insertTask(inProgress, myCategory, "원제목", "원메모", 30, 2,
                LocalDate.of(2099, 1, 1), TaskStatus.UNASSIGNED, 0);

        patch(id, "{\"title\":\"제목만 변경\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("제목만 변경"))   // 변경
                .andExpect(jsonPath("$.data.memo").value("원메모"))          // 유지
                .andExpect(jsonPath("$.data.estimatedMinutes").value(30))    // 유지
                .andExpect(jsonPath("$.data.priority").value(2))            // 유지
                .andExpect(jsonPath("$.data.dueDate").value("2099-01-01"))   // 유지
                .andExpect(jsonPath("$.data.categoryId").value(myCategory.toString())) // 유지
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("AC-E-1 명시적 null = 해제 — memo:null·categoryId:null 보내면 그 필드만 비워지고 나머지 유지")
    void nullClearsOnlyThatField() throws Exception {
        UUID id = insertTask(inProgress, myCategory, "원제목", "원메모", 30, 2,
                LocalDate.of(2099, 1, 1), TaskStatus.UNASSIGNED, 0);

        patch(id, "{\"memo\":null,\"categoryId\":null,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").doesNotExist())          // 해제
                .andExpect(jsonPath("$.data.categoryId").doesNotExist())     // 해제
                .andExpect(jsonPath("$.data.title").value("원제목"))         // 유지
                .andExpect(jsonPath("$.data.estimatedMinutes").value(30))    // 유지
                .andExpect(jsonPath("$.data.priority").value(2))            // 유지
                .andExpect(jsonPath("$.data.dueDate").value("2099-01-01"));  // 유지
    }

    // ---------- AC-E-2 status 금지 ----------

    @Test
    @DisplayName("AC-E-2 요청에 status 포함 → 400 E-COM-001 (상태는 /status 전용)")
    void statusForbidden() throws Exception {
        UUID id = insertTask(inProgress, null, "t", null, null, null, null, TaskStatus.UNASSIGNED, 0);
        patch(id, "{\"title\":\"x\",\"version\":0,\"status\":\"COMPLETED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    // ---------- AC-E-3 생성과 동일 검증 대칭 ----------

    @Test
    @DisplayName("AC-E-3 title 공백·estimatedMinutes 47 → 422 · 타인/부재 categoryId → 404 (생성과 동일)")
    void validationSymmetric() throws Exception {
        UUID id = insertTask(inProgress, null, "t", null, null, null, null, TaskStatus.UNASSIGNED, 0);

        patch(id, "{\"title\":\"   \",\"version\":0}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
        patch(id, "{\"title\":\"x\",\"estimatedMinutes\":47,\"version\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
        patch(id, "{\"title\":\"x\",\"priority\":9999,\"version\":0}") // priority 3단계 검증도 생성과 동일
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
        patch(id, "{\"title\":\"x\",\"categoryId\":\"" + othersCategory + "\",\"version\":0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- AC-E-4 낙관락 ----------

    @Test
    @DisplayName("AC-E-4 stale version → 409 E-COM-006 + details.latest · version 누락 → 400")
    void optimisticLock() throws Exception {
        UUID id = insertTask(inProgress, null, "서버v3", null, null, null, null, TaskStatus.UNASSIGNED, 3);

        patch(id, "{\"title\":\"stale 수정\",\"version\":0}")   // 클라 stale 0 vs 서버 3
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"))
                .andExpect(jsonPath("$.error.details.latest.version").value(3))
                .andExpect(jsonPath("$.error.details.latest.title").value("서버v3")); // 데이터 불변

        patch(id, "{\"title\":\"버전 누락\"}")                   // version 필드 없음
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    // ---------- AC-E-5 태스크 상태 무관 · 프로젝트 상태 가드 ----------

    @Test
    @DisplayName("AC-E-5 COMPLETED 태스크 편집 성공 · PAUSED 프로젝트 성공 · CLOSED 프로젝트 → 422 E-PROJ-005")
    void editByStatus() throws Exception {
        UUID completed = insertTask(inProgress, null, "완료태스크", null, null, null, null, TaskStatus.COMPLETED, 0);
        patch(completed, "{\"title\":\"완료중 수정\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("완료중 수정"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED")); // 편집이 status 안 건드림

        UUID inPaused = insertTask(paused, null, "중지프로젝트 태스크", null, null, null, null, TaskStatus.UNASSIGNED, 0);
        patch(inPaused, "{\"title\":\"중지중 수정\",\"version\":0}").andExpect(status().isOk());

        UUID inClosed = insertTask(closed, null, "종료프로젝트 태스크", null, null, null, null, TaskStatus.UNASSIGNED, 0);
        patch(inClosed, "{\"title\":\"종료중 수정\",\"version\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("project.status"));
    }

    @Test
    @DisplayName("검사 순서 — CLOSED 프로젝트 태스크는 stale version이어도 409 아닌 422 E-PROJ-005 (CLOSED 먼저)")
    void closedGuardBeforeVersion() throws Exception {
        UUID inClosed = insertTask(closed, null, "종료-v3", null, null, null, null, TaskStatus.UNASSIGNED, 3);
        patch(inClosed, "{\"title\":\"수정 시도\",\"version\":0}") // stale version=0
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005")); // 409 아님
    }

    @Test
    @DisplayName("AC-E 부재·타인 taskId → 404")
    void notFound() throws Exception {
        patch(UUID.randomUUID(), "{\"title\":\"x\",\"version\":0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID othersProject = insertProject(OTHER, "타인프로젝트", ProjectStatus.IN_PROGRESS, null);
        UUID othersTask = insertTask(othersProject, null, "타인태스크", null, null, null, null, TaskStatus.UNASSIGNED, 0);
        patch(othersTask, "{\"title\":\"x\",\"version\":0}").andExpect(status().isNotFound());
    }

    // ---------- fixtures ----------

    private ResultActions patch(UUID taskId, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/tasks/" + taskId)
                .header("X-Dev-User", MAIN.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    private UUID insertCategory(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task_categories (task_category_id, user_id, name) VALUES (?, ?, ?)",
                id, userId, name);
        return id;
    }

    private UUID insertTask(UUID projectId, UUID categoryId, String title, String memo,
                            Integer estimatedMinutes, Integer priority, LocalDate dueDate,
                            TaskStatus status, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, projectId, categoryId, title, memo, estimatedMinutes, priority, dueDate,
                status.name(), version, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
