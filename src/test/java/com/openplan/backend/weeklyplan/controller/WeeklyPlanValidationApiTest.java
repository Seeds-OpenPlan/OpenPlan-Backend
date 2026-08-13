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
import org.springframework.http.MediaType;
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
 * 검증 실행·확정 API 통합 테스트 (ST-B2-09). 스냅샷 조립 → 엔진(V1 겹침·차단) 호출 → 영속/dry-run/확정 경로를 고정한다.
 * 규칙 자체는 엔진(전창현) 소관 — 여기선 라우트·조립·영속·409 판정을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class WeeklyPlanValidationApiTest {

    private static final String PATH = "/api/v1/weekly-plans";
    private static final UUID MAIN = UUID.fromString("dddd1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("dddd1111-0000-0000-0000-000000000002");
    private static final LocalDate WEEK = LocalDate.of(2026, 7, 27); // 월요일
    private static final Instant SLOT_A_START = Instant.parse("2026-07-27T02:00:00Z");
    private static final Instant SLOT_A_END = Instant.parse("2026-07-27T03:00:00Z");
    private static final Instant SLOT_OVERLAP_START = Instant.parse("2026-07-27T02:30:00Z"); // A와 겹침
    private static final Instant SLOT_OVERLAP_END = Instant.parse("2026-07-27T03:30:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM validation_issues WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM availability_patterns WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- 검사 (validations) ----------

    @Test
    @DisplayName("검사(저장 초안) — 겹침 2블록 → 200 · savable=false · V1 BLOCK · validation_issues 영속")
    void validatePersistsForSavedDraft() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);
        insertAvailability(MAIN, "MON", "00:00", "23:55"); // V3(가용초과) 억제 → 순수 V1 시나리오
        insertScheduleBlock(MAIN, planId, SLOT_A_START, SLOT_A_END);
        insertScheduleBlock(MAIN, planId, SLOT_OVERLAP_START, SLOT_OVERLAP_END);

        mockMvc.perform(post(PATH + "/" + planId + "/validations").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.savable").value(false))
                .andExpect(jsonPath("$.data.issues[0].ruleId").value("V1_OVERLAP"))
                .andExpect(jsonPath("$.data.issues[0].severity").value("BLOCK"))
                .andExpect(jsonPath("$.data.issues[0].validationIssueId").exists());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM validation_issues WHERE weekly_plan_id = ?", Integer.class, planId);
        org.junit.jupiter.api.Assertions.assertEquals(1, count); // 쌍당 1건 영속
    }

    @Test
    @DisplayName("검사 재실행 — 판정을 전면 교체(중복 누적 없음)")
    void validateReplacesPreviousIssues() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);
        insertAvailability(MAIN, "MON", "00:00", "23:55"); // V3 억제 → 순수 V1
        insertScheduleBlock(MAIN, planId, SLOT_A_START, SLOT_A_END);
        insertScheduleBlock(MAIN, planId, SLOT_OVERLAP_START, SLOT_OVERLAP_END);

        mockMvc.perform(post(PATH + "/" + planId + "/validations").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(post(PATH + "/" + planId + "/validations").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM validation_issues WHERE weekly_plan_id = ?", Integer.class, planId);
        org.junit.jupiter.api.Assertions.assertEquals(1, count); // 누적 아님
    }

    @Test
    @DisplayName("검사(dry-run) — virtualBlocks 겹침 → 200 · dryRun=true · 무영속(validationIssueId null)")
    void validateDryRunNoPersist() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH + "/" + planId + "/validations").header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"virtualBlocks":[
                                  {"blockType":"SCHEDULE","startAt":"2026-07-27T02:00:00Z","endAt":"2026-07-27T03:00:00Z"},
                                  {"blockType":"SCHEDULE","startAt":"2026-07-27T02:30:00Z","endAt":"2026-07-27T03:30:00Z"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.savable").value(false))
                .andExpect(jsonPath("$.data.issues[0].ruleId").value("V1_OVERLAP"))
                .andExpect(jsonPath("$.data.issues[0].validationIssueId").doesNotExist());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM validation_issues WHERE weekly_plan_id = ?", Integer.class, planId);
        org.junit.jupiter.api.Assertions.assertEquals(0, count); // dry-run 무영속
    }

    @Test
    @DisplayName("검사(빈 dry-run) — virtualBlocks 빈 배열 → savable=true · 이슈 없음")
    void validateEmptyDryRun() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH + "/" + planId + "/validations").header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"virtualBlocks\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.savable").value(true))
                .andExpect(jsonPath("$.data.issues.length()").value(0));
    }

    @Test
    @DisplayName("검사 — 위반 없는 빈 계획 → savable=true · 이슈 없음")
    void validateCleanSavable() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH + "/" + planId + "/validations").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savable").value(true))
                .andExpect(jsonPath("$.data.issues.length()").value(0));
    }

    @Test
    @DisplayName("검사 — 없는/타인 계획 → 404")
    void validateNotFound() throws Exception {
        mockMvc.perform(post(PATH + "/" + UUID.randomUUID() + "/validations").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- 확정 (confirmation) ----------

    @Test
    @DisplayName("확정 — 차단 0건(빈 계획) → 200 · status=CONFIRMED · confirmedAt 세팅")
    void confirmSucceeds() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);

        mockMvc.perform(post(PATH + "/" + planId + "/confirmation").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").exists());

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM weekly_plans WHERE weekly_plan_id = ?", String.class, planId);
        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMED", dbStatus);
    }

    @Test
    @DisplayName("확정 — 차단(겹침) 존재 → 409 E-PLAN-004 · details.issues · DRAFT 유지")
    void confirmBlockedByOverlap() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);
        insertScheduleBlock(MAIN, planId, SLOT_A_START, SLOT_A_END);
        insertScheduleBlock(MAIN, planId, SLOT_OVERLAP_START, SLOT_OVERLAP_END);

        mockMvc.perform(post(PATH + "/" + planId + "/confirmation").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-PLAN-004"))
                .andExpect(jsonPath("$.error.details.issues[0].ruleId").value("V1_OVERLAP"));

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM weekly_plans WHERE weekly_plan_id = ?", String.class, planId);
        org.junit.jupiter.api.Assertions.assertEquals("DRAFT", dbStatus); // 확정 안 됨
    }

    @Test
    @DisplayName("확정 — 없는/타인 계획 → 404")
    void confirmNotFound() throws Exception {
        mockMvc.perform(post(PATH + "/" + UUID.randomUUID() + "/confirmation").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
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

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', 0, now())
                """, id, userId, weekStart, weekStart.plusDays(6));
        return id;
    }

    private void insertAvailability(UUID userId, String weekday, String start, String end) {
        jdbc.update("""
                INSERT INTO availability_patterns (availability_pattern_id, user_id, weekday, start_time, end_time, is_active)
                VALUES (?, ?, ?, ?, ?, true)
                """, UUID.randomUUID(), userId, weekday,
                java.time.LocalTime.parse(start), java.time.LocalTime.parse(end));
    }

    private void insertScheduleBlock(UUID userId, UUID planId, Instant start, Instant end) {
        UUID scheduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (schedule_id, user_id, title, start_at, end_at, status, version, created_at)
                VALUES (?, ?, '일정', ?, ?, 'ACTIVE', 0, now())
                """, scheduleId, userId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, NULL, ?, 'SCHEDULE', ?, ?, 'SCHEDULED', now())
                """, UUID.randomUUID(), planId, scheduleId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
    }
}
