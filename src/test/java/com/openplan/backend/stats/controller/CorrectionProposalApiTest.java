package com.openplan.backend.stats.controller;

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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보정 제안 API 통합 테스트 (SS-11 / RB-STAT-02) — {@code GET /stats/correction-proposals}.
 *
 * <p>산출식 자체는 {@code CorrectionProposalPolicyTest}가 골든으로 잠그므로 여기서는 <b>배선</b>을 본다:
 * 스코프 선택·폴백 없음([G2]), 집계가 as-built deviations와 같은 산법인지, null 3사유의 실제 응답 모양,
 * 참조 404·422·사용자 격리.
 *
 * <p><b>고정 시계가 필요 없다</b> — 집계 창이 전체 이력(ASSUMPTION-CP1)이라 결과가 시계에 의존하지 않는다.
 * 그럼에도 {@code FixedClockConfig}를 import 하는 것은 다른 stats 테스트와 인프라를 맞추기 위함이며,
 * 로그 시각을 아무 값으로 넣어도 결과가 같다는 것 자체가 [G4]로 검증된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class CorrectionProposalApiTest {

    private static final String PATH = "/api/v1/stats/correction-proposals";
    private static final UUID MAIN = UUID.fromString("eeee0003-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeee0003-0000-0000-0000-000000000002");
    private static final Instant T0 = Instant.parse("2026-05-01T01:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID project;
    private UUID category;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM execution_logs WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM task_categories WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        project = insertProject(MAIN, "프로젝트");
        category = insertCategory(MAIN, "공부");
    }

    // ═══════════════ 제안 성립 (AC-1·AC-2) ═══════════════

    @Test
    @DisplayName("AC-2 카테고리 스코프 골든 — 태스크 1건(est=50)+로그 3건(합 60) → r=+20 → 70")
    void categoryScopeProposal() throws Exception {
        // 스토리 AC-2는 "est=60·Σactual=72"로 적혀 있으나 ck_exec_actual(5분 배수)이 72를 허용하지 않는다.
        // r=+20이라는 규범은 그대로 두고 DB 제약을 만족하는 등가 수치로 구성한다(50 → 60).
        UUID task = insertTask(project, category, 50);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20); // Σactual=60, Σestimated=50(태스크당 1회) → r=+20

        mockMvc.perform(req(MAIN).param("categoryId", category.toString())
                        .param("estimatedMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedEstimatedMinutes").value(70))
                .andExpect(jsonPath("$.data.basis").value("해당 카테고리 편차율 +20% 반영"))
                .andExpect(jsonPath("$.data.sampleSize").value(3))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    @DisplayName("예상시간은 태스크당 1회만 합산한다 — 로그 3건이라고 180이 되지 않는다")
    void estimatedCountedOncePerTask() throws Exception {
        // 로그마다 est를 합산하면 Σestimated=150, r=(60-150)*100/150=-60 → 60×0.4=24 → 25가 나온다.
        UUID task = insertTask(project, category, 50);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);

        mockMvc.perform(req(MAIN).param("categoryId", category.toString())
                        .param("estimatedMinutes", "60"))
                .andExpect(jsonPath("$.data.proposedEstimatedMinutes").value(70)); // 25가 아니다
    }

    // ═══════════════ [G2] 스코프 (AC-9·AC-10) ═══════════════

    @Test
    @DisplayName("AC-9 스코프 우선 — 둘 다 제공되면 카테고리만 집계(프로젝트 전용 이력 미반영)")
    void categoryWinsOverProject() throws Exception {
        UUID inCategory = insertTask(project, category, 50);
        insertLog(MAIN, inCategory, 20);
        insertLog(MAIN, inCategory, 20);
        insertLog(MAIN, inCategory, 20); // 카테고리: r=+20

        // 같은 프로젝트이지만 카테고리가 다른 태스크 — 카테고리 스코프면 산입되면 안 된다
        UUID otherCategory = insertCategory(MAIN, "운동");
        UUID outOfCategory = insertTask(project, otherCategory, 50);
        insertLog(MAIN, outOfCategory, 600); // 산입되면 r이 폭증한다

        mockMvc.perform(req(MAIN).param("categoryId", category.toString())
                        .param("projectId", project.toString())
                        .param("estimatedMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedEstimatedMinutes").value(70)) // 카테고리 값 그대로
                .andExpect(jsonPath("$.data.basis").value("해당 카테고리 편차율 +20% 반영"))
                .andExpect(jsonPath("$.data.sampleSize").value(3));
    }

    @Test
    @DisplayName("AC-9 폴백 없음 — 카테고리 이력 2건·프로젝트 이력 5건이어도 data 생략")
    void noSilentFallbackToProject() throws Exception {
        UUID inCategory = insertTask(project, category, 60);
        insertLog(MAIN, inCategory, 30);
        insertLog(MAIN, inCategory, 30); // 카테고리 2건 — 하한 미만

        UUID otherCategory = insertCategory(MAIN, "운동");
        UUID projectOnly = insertTask(project, otherCategory, 60);
        for (int i = 0; i < 5; i++) {
            insertLog(MAIN, projectOnly, 30); // 프로젝트 전체로는 7건이지만 폴백하지 않는다
        }

        expectNoProposal(req(MAIN).param("categoryId", category.toString())
                .param("projectId", project.toString())
                .param("estimatedMinutes", "60"));
    }

    @Test
    @DisplayName("projectId만 제공 → 프로젝트 스코프 · basis 문구도 프로젝트")
    void projectScope() throws Exception {
        UUID task = insertTask(project, null, 50);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);

        mockMvc.perform(req(MAIN).param("projectId", project.toString())
                        .param("estimatedMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis").value("해당 프로젝트 편차율 +20% 반영"));
    }

    @Test
    @DisplayName("AC-10 둘 다 미제공 → 전체 이력 스코프 · 카테고리/프로젝트 무관 전 로그 산입")
    void allScope() throws Exception {
        UUID a = insertTask(project, category, 50);
        insertLog(MAIN, a, 20);
        insertLog(MAIN, a, 20);
        insertLog(MAIN, a, 20);

        UUID otherProject = insertProject(MAIN, "다른프로젝트");
        UUID b = insertTask(otherProject, null, 50); // 카테고리 없음·다른 프로젝트 — 전체 스코프엔 포함
        insertLog(MAIN, b, 60);

        // Σestimated=100, Σactual=120 → r=+20
        mockMvc.perform(req(MAIN).param("estimatedMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis").value("전체 수행 이력 편차율 +20% 반영"))
                .andExpect(jsonPath("$.data.sampleSize").value(4));
    }

    // ═══════════════ null 3사유 (AC-6·7·8) ═══════════════

    @Test
    @DisplayName("AC-6 표본 부족(2건) → data 생략 · 3건이면 제안 (경계 양쪽)")
    void sampleBoundary() throws Exception {
        UUID task = insertTask(project, category, 50);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);

        expectNoProposal(req(MAIN).param("categoryId", category.toString())
                .param("estimatedMinutes", "60"));

        insertLog(MAIN, task, 20); // 3건째
        mockMvc.perform(req(MAIN).param("categoryId", category.toString())
                        .param("estimatedMinutes", "60"))
                .andExpect(jsonPath("$.data.sampleSize").value(3));
    }

    @Test
    @DisplayName("예상시간 없는 태스크의 이력은 편차 계산에서 제외 — 섞여 있어도 편차율이 부풀지 않는다")
    void tasksWithoutEstimateExcludedFromRate() throws Exception {
        // 측정 가능한 쪽: 예상 50분, 실제 60분(20×3) → r=+20 이어야 한다
        UUID measurable = insertTask(project, category, 50);
        insertLog(MAIN, measurable, 20);
        insertLog(MAIN, measurable, 20);
        insertLog(MAIN, measurable, 20);

        // 예상시간이 없는 태스크 — 실제시간만 있고 비교 기준이 없다.
        // 거르지 않으면 Σactual만 600 늘고 Σestimated는 그대로라 r이 폭증한다.
        UUID unmeasurable = insertTask(project, category, null);
        insertLog(MAIN, unmeasurable, 200);
        insertLog(MAIN, unmeasurable, 200);
        insertLog(MAIN, unmeasurable, 200);

        mockMvc.perform(req(MAIN).param("categoryId", category.toString())
                        .param("estimatedMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedEstimatedMinutes").value(70)) // 거르지 않으면 780이 된다
                .andExpect(jsonPath("$.data.basis").value("해당 카테고리 편차율 +20% 반영"))
                .andExpect(jsonPath("$.data.sampleSize").value(3)); // 6이 아니라 3 — 근거와 계산이 일치해야 한다
    }

    @Test
    @DisplayName("측정 가능한 이력만으로 표본 하한을 센다 — 예상 없는 로그로는 3건을 채울 수 없다")
    void unmeasurableLogsDoNotFillSampleQuota() throws Exception {
        UUID measurable = insertTask(project, category, 50);
        insertLog(MAIN, measurable, 20);
        insertLog(MAIN, measurable, 20); // 측정 가능 2건 — 하한 미만

        UUID unmeasurable = insertTask(project, category, null);
        insertLog(MAIN, unmeasurable, 60);
        insertLog(MAIN, unmeasurable, 60); // 전체로는 4건이지만 표본은 여전히 2건

        expectNoProposal(req(MAIN).param("categoryId", category.toString())
                .param("estimatedMinutes", "60"));
    }

    @Test
    @DisplayName("AC-7 Σestimated=0(예상시간 전부 null) → data 생략 — 편차율이 정의되지 않는다")
    void zeroEstimatedSum() throws Exception {
        UUID task = insertTask(project, category, null);
        insertLog(MAIN, task, 30);
        insertLog(MAIN, task, 30);
        insertLog(MAIN, task, 30);

        expectNoProposal(req(MAIN).param("categoryId", category.toString())
                .param("estimatedMinutes", "60"));
    }

    @Test
    @DisplayName("AC-8 estimatedMinutes 미제공 → 이력이 충분해도 data 생략")
    void missingEstimatedMinutes() throws Exception {
        UUID task = insertTask(project, category, 50);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);

        expectNoProposal(req(MAIN).param("categoryId", category.toString()));
    }

    // ═══════════════ 오류 경로 (AC-11·12·13) ═══════════════

    @Test
    @DisplayName("AC-11 참조 404 — 부재·타인 categoryId/projectId (구분 불가)")
    void referenceNotFound() throws Exception {
        UUID othersCategory = insertCategory(OTHER, "남의카테고리");
        UUID othersProject = insertProject(OTHER, "남의프로젝트");

        for (String[] p : new String[][]{
                {"categoryId", UUID.randomUUID().toString()},
                {"categoryId", othersCategory.toString()},
                {"projectId", UUID.randomUUID().toString()},
                {"projectId", othersProject.toString()}}) {
            mockMvc.perform(req(MAIN).param(p[0], p[1]).param("estimatedMinutes", "60"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("E-COM-004"));
        }
    }

    @Test
    @DisplayName("AC-11 UUID 형식 오류 → 400 E-COM-001")
    void malformedUuid() throws Exception {
        mockMvc.perform(req(MAIN).param("categoryId", "not-a-uuid").param("estimatedMinutes", "60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("AC-12 estimatedMinutes 5분 단위 위반 → 422 · field·rule=step · 문구 해석 확인")
    void estimatedMinutesStep() throws Exception {
        for (String bad : new String[]{"0", "-5", "62"}) {
            mockMvc.perform(req(MAIN).param("estimatedMinutes", bad))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                    .andExpect(jsonPath("$.error.details.fields[0].field").value("estimatedMinutes"))
                    .andExpect(jsonPath("$.error.details.fields[0].rule").value("step"))
                    // 카탈로그 키 해석 확인 — 폴백("일시적인 오류…")으로 새면 실패한다
                    .andExpect(jsonPath("$.error.details.fields[0].message")
                            .value("예상 소요 시간은 5분 단위의 0보다 큰 값이어야 합니다."));
        }
    }

    @Test
    @DisplayName("AC-13 사용자 격리 — 타인 이력은 집계·sampleSize에 미산입(내 이력 0 → data 생략)")
    void userIsolation() throws Exception {
        UUID othersProject = insertProject(OTHER, "남의프로젝트");
        UUID othersTask = insertTask(othersProject, null, 60);
        for (int i = 0; i < 5; i++) {
            insertLog(OTHER, othersTask, 240); // 타인 이력만 풍부
        }

        expectNoProposal(req(MAIN).param("estimatedMinutes", "60"));
    }

    @Test
    @DisplayName("AC-13 미시드 사용자 → 401 E-COM-002")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(PATH).header("X-Dev-User", "99999999-9999-9999-9999-999999999999")
                        .param("estimatedMinutes", "60"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    // ═══════════════ [G4] 결정성 ═══════════════

    @Test
    @DisplayName("결정성 — 같은 입력 2회가 문자 단위 동일 (전체 이력이라 시계 비의존)")
    void deterministic() throws Exception {
        UUID task = insertTask(project, category, 50);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);
        insertLog(MAIN, task, 20);

        String first = body(req(MAIN).param("categoryId", category.toString())
                .param("estimatedMinutes", "60"));
        String second = body(req(MAIN).param("categoryId", category.toString())
                .param("estimatedMinutes", "60"));

        assertThat(first).isEqualTo(second);
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(UUID userId) {
        return get(PATH).header("X-Dev-User", userId.toString());
    }

    /**
     * 제안 불가 응답 단언. 정본은 {@code data: null}이라고 쓰지만 {@code ApiResponse}가 NON_NULL이라
     * 실제 직렬화는 <b>{@code data} 키 자체가 없는 것</b>이다 — FE가 `data === null`로 분기하면 깨진다.
     * 스키마상 {@code data}는 required가 아니라 위반은 아니다.
     */
    private void expectNoProposal(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    // ---------- fixtures ----------

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
                """, id, userId, name, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertCategory(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task_categories (task_category_id, user_id, name, sort_order, created_at)
                VALUES (?, ?, ?, 0, ?)
                """, id, userId, name, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, UUID categoryId, Integer estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, ?, '테스트 태스크', NULL, ?, NULL, NULL, 'IN_PROGRESS', 0, ?)
                """, id, projectId, categoryId, estimatedMinutes, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    /** 시각은 아무 값이어도 된다 — 집계 창이 전체 이력이라 결과에 영향이 없다(CP-1). */
    private void insertLog(UUID userId, UUID taskId, int actualMinutes) {
        jdbc.update("""
                INSERT INTO execution_logs (execution_log_id, user_id, task_id, plan_block_id,
                                            started_at, ended_at, actual_minutes, result, memo, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, 'COMPLETED', NULL, ?)
                """, UUID.randomUUID(), userId, taskId,
                OffsetDateTime.ofInstant(T0, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(T0.plusSeconds(actualMinutes * 60L), ZoneOffset.UTC),
                actualMinutes, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
