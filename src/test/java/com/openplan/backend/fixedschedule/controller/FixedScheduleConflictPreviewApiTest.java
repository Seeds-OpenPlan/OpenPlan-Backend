package com.openplan.backend.fixedschedule.controller;

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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고정 일정 충돌 미리보기 API 통합 테스트 (FIX-07·08 / {@code POST /fixed-schedules/conflict-previews}).
 *
 * <p>고정하는 계약: 저장된 주간 계획 전량 대상(과거 주 포함)·주차 오름차순 · 충돌 있는 주만 ·
 * 후보발 V2만(기존 충돌 제외) · 무영속(validationIssueId null, DB 무변경) · 편집 시 자기 자신 제외 ·
 * 편집 대상의 주차 예외 주 건너뜀 · 부재/타인 fixedScheduleId 404 · 값 오류 422.
 *
 * <p>시간대는 FixedClockConfig의 Asia/Seoul — UTC 02:00 = KST 11:00(같은 날)이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class FixedScheduleConflictPreviewApiTest {

    private static final String PATH = "/api/v1/fixed-schedules/conflict-previews";
    private static final UUID MAIN = UUID.fromString("cccc1111-0000-0000-0000-000000000011");
    private static final UUID OTHER = UUID.fromString("cccc1111-0000-0000-0000-000000000012");

    private static final LocalDate WEEK_1 = LocalDate.of(2026, 7, 27); // 월요일
    private static final LocalDate WEEK_2 = LocalDate.of(2026, 8, 3);  // 다음 주 월요일
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    /** WEEK_1 월요일 KST 11:00~12:00 블록. */
    private static final Instant W1_MON_11 = Instant.parse("2026-07-27T02:00:00Z");
    private static final Instant W1_MON_12 = Instant.parse("2026-07-27T03:00:00Z");
    /** WEEK_2 월요일 KST 11:00~12:00 블록. */
    private static final Instant W2_MON_11 = Instant.parse("2026-08-03T02:00:00Z");
    private static final Instant W2_MON_12 = Instant.parse("2026-08-03T03:00:00Z");

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
        jdbc.update("DELETE FROM fixed_schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM availability_patterns WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("후보가 블록과 겹침 → 200 · 그 주 1건 · V2 BLOCK · validationIssueId=null · counterpartId=null(생성)")
    void conflictDetected() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_1);
        UUID block = insertScheduleBlock(MAIN, plan, W1_MON_11, W1_MON_12);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].weekStartDate").value("2026-07-27"))
                .andExpect(jsonPath("$.data[0].issues.length()").value(1))
                .andExpect(jsonPath("$.data[0].issues[0].ruleId").value("V2_FIXED_CONFLICT"))
                .andExpect(jsonPath("$.data[0].issues[0].severity").value("BLOCK"))
                .andExpect(jsonPath("$.data[0].issues[0].planBlockId").value(block.toString()))
                .andExpect(jsonPath("$.data[0].issues[0].reason").isNotEmpty())
                // 무영속 — 저장된 이슈 id도, 아직 없는 고정 일정 id도 내보내지 않는다
                .andExpect(jsonPath("$.data[0].issues[0].validationIssueId").doesNotExist())
                .andExpect(jsonPath("$.data[0].issues[0].counterpartId").doesNotExist());
    }

    @Test
    @DisplayName("무영속 — 미리보기 후 validation_issues·fixed_schedules 무변경")
    void persistsNothing() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_1);
        insertScheduleBlock(MAIN, plan, W1_MON_11, W1_MON_12);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, null)))
                .andExpect(status().isOk());

        assertCount("SELECT count(*) FROM validation_issues WHERE weekly_plan_id = ?", 0L, plan);
        assertCount("SELECT count(*) FROM fixed_schedules WHERE user_id = ?", 0L, MAIN);
    }

    @Test
    @DisplayName("겹치지 않는 후보 → 200 · 빈 배열 (오류 아님)")
    void noConflict() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_1);
        insertScheduleBlock(MAIN, plan, W1_MON_11, W1_MON_12);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "14:00", "15:00", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("저장된 주간 계획이 없으면 → 200 · 빈 배열")
    void noWeeklyPlans() throws Exception {
        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("여러 주 충돌 → 주차 오름차순으로 전부 (과거 주도 뺴지 않는다 — 정본 '저장된 주간 계획 대상')")
    void multipleWeeksAscending() throws Exception {
        UUID plan2 = insertWeeklyPlan(MAIN, WEEK_2); // 늦은 주를 먼저 심어 정렬이 삽입순이 아님을 보인다
        UUID plan1 = insertWeeklyPlan(MAIN, WEEK_1);
        insertScheduleBlock(MAIN, plan2, W2_MON_11, W2_MON_12);
        insertScheduleBlock(MAIN, plan1, W1_MON_11, W1_MON_12);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].weekStartDate").value("2026-07-27"))
                .andExpect(jsonPath("$.data[1].weekStartDate").value("2026-08-03"));
    }

    @Test
    @DisplayName("후보 유효기간 밖 주는 충돌 없음 (V2가 effectiveFrom/To로 자체 제외)")
    void outsideCandidateEffectiveRangeExcluded() throws Exception {
        UUID plan1 = insertWeeklyPlan(MAIN, WEEK_1);
        UUID plan2 = insertWeeklyPlan(MAIN, WEEK_2);
        insertScheduleBlock(MAIN, plan1, W1_MON_11, W1_MON_12);
        insertScheduleBlock(MAIN, plan2, W2_MON_11, W2_MON_12);

        // 후보가 둘째 주부터 유효 → 첫째 주는 나오면 안 된다
        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", "2026-08-03", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].weekStartDate").value("2026-08-03"));
    }

    @Test
    @DisplayName("후보와 무관한 기존 충돌은 미포함 (기존 고정 일정↔블록 V2는 '추가하면 생기는 충돌'이 아님)")
    void preexistingConflictExcluded() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_1);
        insertScheduleBlock(MAIN, plan, W1_MON_11, W1_MON_12);
        insertFixedSchedule(MAIN, "기존수업", "MON", "11:00", "12:00", null, null); // 이미 블록과 충돌 중

        // 후보는 겹치지 않는 시간 → 기존 충돌이 있어도 결과는 비어야 한다
        mockMvc.perform(preview(MAIN, candidate("새수업", "MON", "14:00", "15:00", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("편집 — 자기 자신 제외: 겹치던 일정을 안 겹치는 시각으로 옮기면 충돌 0건")
    void editExcludesItself() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_1);
        insertScheduleBlock(MAIN, plan, W1_MON_11, W1_MON_12);
        UUID existing = insertFixedSchedule(MAIN, "수업", "MON", "11:00", "12:00", null, null);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "14:00", "15:00", null, null, existing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0)); // 옛 자신과의 충돌을 잡으면 실패
    }

    @Test
    @DisplayName("편집 — 여전히 겹치면 충돌 · counterpartId=기존 ID(생성과 달리 가리킬 행이 있다)")
    void editStillConflicting() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK_1);
        insertScheduleBlock(MAIN, plan, W1_MON_11, W1_MON_12);
        UUID existing = insertFixedSchedule(MAIN, "수업", "MON", "09:00", "10:00", null, null);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:30", "12:30", null, null, existing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].issues[0].ruleId").value("V2_FIXED_CONFLICT"))
                .andExpect(jsonPath("$.data[0].issues[0].counterpartId").value(existing.toString()));
    }

    @Test
    @DisplayName("편집 — 주차 예외(PLAN-33)가 걸린 주는 건너뛴다 (저장해도 그 주엔 안 생길 충돌)")
    void editSkipsExceptedWeek() throws Exception {
        UUID plan1 = insertWeeklyPlan(MAIN, WEEK_1);
        UUID plan2 = insertWeeklyPlan(MAIN, WEEK_2);
        insertScheduleBlock(MAIN, plan1, W1_MON_11, W1_MON_12);
        insertScheduleBlock(MAIN, plan2, W2_MON_11, W2_MON_12);
        UUID existing = insertFixedSchedule(MAIN, "수업", "MON", "09:00", "10:00", null, null);
        insertWeekException(existing, WEEK_1); // 첫째 주만 제외

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, existing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].weekStartDate").value("2026-08-03"));
    }

    @Test
    @DisplayName("타인 주간 계획은 대상 아님 (사용자 격리)")
    void otherUsersPlansNotScanned() throws Exception {
        UUID otherPlan = insertWeeklyPlan(OTHER, WEEK_1);
        insertScheduleBlock(OTHER, otherPlan, W1_MON_11, W1_MON_12);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("없는 fixedScheduleId → 404 E-COM-004 (값 검증보다 먼저)")
    void unknownFixedScheduleIdNotFound() throws Exception {
        // 시각도 함께 틀리게 준다 — 422가 아니라 404가 이겨야 한다
        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "12:00", "11:00", null, null, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 fixedScheduleId → 404 (존재 은닉)")
    void otherUsersFixedScheduleHidden() throws Exception {
        UUID others = insertFixedSchedule(OTHER, "남의수업", "MON", "09:00", "10:00", null, null);

        mockMvc.perform(preview(MAIN, candidate("수업", "MON", "11:00", "12:00", null, null, others)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("값 오류 → 422 E-COM-009 (미정의 weekday · 시각 역전 · 5분 단위 위반 · 제목 공백)")
    void invalidCandidateRejected() throws Exception {
        String[][] bad = {
                {"수업", "MONDAY", "11:00", "12:00"},  // 미정의 weekday
                {"수업", "MON", "12:00", "11:00"},     // 시작 >= 종료
                {"수업", "MON", "11:03", "12:00"},     // 5분 단위 아님
                {"   ", "MON", "11:00", "12:00"},      // 제목 공백
        };
        for (String[] c : bad) {
            mockMvc.perform(preview(MAIN, candidate(c[0], c[1], c[2], c[3], null, null, null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("E-COM-009"));
        }
    }

    @Test
    @DisplayName("candidate 누락 → 422 E-COM-009 (500 아님)")
    void missingCandidateRejected() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder preview(
            UUID userId, String candidateJson) {
        return post(PATH).header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidate\":" + candidateJson + "}");
    }

    private static String candidate(String title, String weekday, String startTime, String endTime,
                                    String startDate, String endDate, UUID fixedScheduleId) {
        return "{\"title\":\"" + title + "\",\"weekday\":\"" + weekday + "\""
                + ",\"startTime\":\"" + startTime + "\",\"endTime\":\"" + endTime + "\""
                + ",\"startDate\":" + quoteOrNull(startDate)
                + ",\"endDate\":" + quoteOrNull(endDate)
                + ",\"fixedScheduleId\":" + (fixedScheduleId == null ? "null" : "\"" + fixedScheduleId + "\"")
                + "}";
    }

    private static String quoteOrNull(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private void assertCount(String sql, long expected, Object... args) {
        Long actual = jdbc.queryForObject(sql, Long.class, args);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
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

    /** SCHEDULE 블록 — 태스크 없이 시간만 있으면 V2 판정에 충분하다(TASK 사실 조립 불요). */
    private UUID insertScheduleBlock(UUID userId, UUID planId, Instant start, Instant end) {
        UUID scheduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (schedule_id, user_id, title, start_at, end_at, status, version, created_at)
                VALUES (?, ?, '일정', ?, ?, 'SCHEDULED', 0, now())
                """, scheduleId, userId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC));

        UUID blockId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, NULL, ?, 'SCHEDULE', ?, ?, 'SCHEDULED', now())
                """, blockId, planId, scheduleId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
        return blockId;
    }

    private UUID insertFixedSchedule(UUID userId, String title, String weekday, String startTime,
                                     String endTime, LocalDate startDate, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fixed_schedules (fixed_schedule_id, user_id, title, weekday, start_time, end_time,
                                             start_date, end_date, source, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', 'ACTIVE', 0, ?)
                """, id, userId, title, weekday, LocalTime.parse(startTime), LocalTime.parse(endTime),
                startDate, endDate, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertWeekException(UUID fixedScheduleId, LocalDate weekStartDate) {
        jdbc.update("""
                INSERT INTO fixed_schedule_week_exceptions (exception_id, fixed_schedule_id, week_start_date)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), fixedScheduleId, weekStartDate);
    }
}
