package com.openplan.backend.task.controller;

import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 태스크 생성 API 통합 테스트 (PROJ-17 / EP-2 · AC-C-1~8). 검사 순서(404→422 CLOSED→422 필드→404 카테고리)와
 * 지명 테스트 [T2](title 키 부재)·[T3](과거 dueDate 성공 비대칭)를 고정한다.
 * 고정 시계({@link FixedClockConfig}, FIXED_TODAY=2026-07-15)로 판정을 결정적으로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectTaskCreateApiTest {

    private static final UUID MAIN = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
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

    // ---------- AC-C-1 · AC-C-2 정상 ----------

    @Test
    @DisplayName("AC-C-1 정상 생성 → 201 · status=UNASSIGNED · version=0 · taskId 포함")
    void createOk() throws Exception {
        post(inProgress, """
                {"title":"첫 태스크","memo":"메모","estimatedMinutes":30,"priority":1,"dueDate":"2099-12-31"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskId").exists())
                .andExpect(jsonPath("$.data.projectId").value(inProgress.toString()))
                .andExpect(jsonPath("$.data.title").value("첫 태스크"))
                .andExpect(jsonPath("$.data.estimatedMinutes").value(30))
                .andExpect(jsonPath("$.data.status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("AC-C-2 title만 제공 → 201 · 선택 필드 null 허용(estimatedMinutes 서버 기본값 미주입)")
    void createTitleOnly() throws Exception {
        post(inProgress, "{\"title\":\"제목만\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.data.estimatedMinutes").doesNotExist())
                .andExpect(jsonPath("$.data.memo").doesNotExist())
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
    }

    // ---------- AC-C-3 · [T2] title 규칙 ----------

    @Test
    @DisplayName("AC-C-3 title 공백만/200자 초과 → 422 E-COM-009 · 정확히 200자 성공 · trim 영속")
    void titleRules() throws Exception {
        post(inProgress, "{\"title\":\"   \"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));

        String over = "가".repeat(201);
        post(inProgress, "{\"title\":\"" + over + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));

        String exact = "가".repeat(200);
        post(inProgress, "{\"title\":\"  " + exact + "  \"}") // 앞뒤 공백 → trim 후 정확히 200자
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value(exact));
    }

    @Test
    @DisplayName("[T2] title 키 자체 부재(null) → 422 E-COM-009 (500 금지 — 첫 줄 null 가드)")
    void titleKeyAbsent() throws Exception {
        post(inProgress, "{\"memo\":\"제목 키 없음\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ---------- AC-C-4 estimatedMinutes ----------

    @Test
    @DisplayName("AC-C-4 estimatedMinutes 47·0·-5 → 422 · 5=최소 유효값 성공")
    void estimatedMinutesRules() throws Exception {
        post(inProgress, "{\"title\":\"t\",\"estimatedMinutes\":47}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
        post(inProgress, "{\"title\":\"t\",\"estimatedMinutes\":0}").andExpect(status().isUnprocessableEntity());
        post(inProgress, "{\"title\":\"t\",\"estimatedMinutes\":-5}").andExpect(status().isUnprocessableEntity());
        post(inProgress, "{\"title\":\"t\",\"estimatedMinutes\":5}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.estimatedMinutes").value(5));
    }

    // ---------- AC-C-5 카테고리 소유(D-8) ----------

    @Test
    @DisplayName("AC-C-5 본인 카테고리 성공·영속 · 타인/부재 categoryId → 404 E-COM-004")
    void categoryOwnership() throws Exception {
        post(inProgress, "{\"title\":\"t\",\"categoryId\":\"" + myCategory + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryId").value(myCategory.toString()));

        post(inProgress, "{\"title\":\"t\",\"categoryId\":\"" + othersCategory + "\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        post(inProgress, "{\"title\":\"t\",\"categoryId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- AC-C-6 · [T3] 과거 dueDate 허용(비대칭) ----------

    @Test
    @DisplayName("[T3] 과거 dueDate 생성 → 성공 (D-11 — 프로젝트와 의도적 비대칭)")
    void pastDueDateAllowed() throws Exception {
        post(inProgress, "{\"title\":\"지난 마감\",\"dueDate\":\"2000-01-01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dueDate").value("2000-01-01"));
    }

    // ---------- AC-C-7 프로젝트 스코프/상태 가드(D-10) ----------

    @Test
    @DisplayName("AC-C-7 부재·타인 projectId → 404 · PAUSED → 201 · CLOSED → 422 E-PROJ-005")
    void projectScopeAndClosedGuard() throws Exception {
        post(UUID.randomUUID(), "{\"title\":\"t\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID others = insertProject(OTHER, "타인프로젝트", ProjectStatus.IN_PROGRESS, null);
        post(others, "{\"title\":\"t\"}").andExpect(status().isNotFound());

        post(paused, "{\"title\":\"중지중 생성\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("UNASSIGNED"));

        post(closed, "{\"title\":\"종료중 생성\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("project.status"));
    }

    // ---------- AC-C-8 status 필드 금지 / 파싱 갭(known-gap) ----------

    @Test
    @DisplayName("AC-C-8 요청에 status 포함 → 400 E-COM-001 (침묵 무시 금지)")
    void statusFieldForbidden() throws Exception {
        post(inProgress, "{\"title\":\"t\",\"status\":\"ANYTHING\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("AC-C-8 known-gap: JSON 타입 불일치(dueDate 비날짜)는 글로벌 핸들러 갭으로 현재 500 "
            + "— 매핑 추가 시 400 E-COM-001로 전환(exceptions §5)")
    void malformedBodyIsKnownGap500() throws Exception {
        // ⚠ ST-B2-01 W6 CONCERNS 이월: GlobalExceptionHandler에 HttpMessageNotReadable·
        // MethodArgumentTypeMismatch → 400 매핑이 없어 파싱/타입 오류가 500으로 샌다(global 소유·본 스토리 미수정).
        // 매핑이 추가되면 이 단언을 400 E-COM-001로 갱신해야 한다(신호 역할).
        post(inProgress, "{\"title\":\"t\",\"dueDate\":\"not-a-date\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("E-COM-005"));
    }

    // ---------- fixtures ----------

    private org.springframework.test.web.servlet.ResultActions post(UUID projectId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/projects/" + projectId + "/tasks")
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
}
