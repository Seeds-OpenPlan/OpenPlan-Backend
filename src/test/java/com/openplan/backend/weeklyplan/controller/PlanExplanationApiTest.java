package com.openplan.backend.weeklyplan.controller;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계획 설명 API 통합 테스트 — {@code POST /weekly-plans/{planId}/explanation}.
 *
 * <p>테스트 프로파일(application-test.yaml)은 {@code op.ai.base-url}을 설정하지 않으므로
 * {@code AiConfig}가 통째로 안 올라가고 {@code AiPlanExplainClient} 빈도 없다 — 그래서 소유자의
 * 정상 요청도 "설명 접합 미설정" 경로를 타 503(E-PLAN-005)으로 응답해야 한다. 성공 매핑·실제 호출
 * 실패 분기는 DB 없이 도는 {@link com.openplan.backend.weeklyplan.service.PlanExplanationServiceTest}가 담당한다
 * ({@code AiConfigWiringTest}가 "설정이 없으면 AI 접합이 안 올라간다"를 스프링 배선 레벨에서 이미 고정한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PlanExplanationApiTest {

    private static final UUID MAIN = UUID.fromString("dddd3333-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("dddd3333-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3); // 월요일

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("설명 접합 미설정(로컬·CI 기본) → 503 E-PLAN-005 — 가짜 설명을 지어내지 않는다")
    void 설명접합_미설정이면_503() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("E-PLAN-005"));
    }

    @Test
    @DisplayName("없는/타인 계획 → 404 — 설명 접합 설정 여부와 무관하게 소유 확인이 먼저다")
    void planNotFound() throws Exception {
        mockMvc.perform(post(PATH(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인의 계획 → 404(존재 은닉) — 소유 스코프를 벗어나면 접합 호출 전에 끊긴다")
    void othersPlanNotFound() throws Exception {
        UUID plan = insertWeeklyPlan(OTHER, WEEK);

        mockMvc.perform(post(PATH(plan)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private String PATH(UUID planId) {
        return "/api/v1/weekly-plans/" + planId + "/explanation";
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
