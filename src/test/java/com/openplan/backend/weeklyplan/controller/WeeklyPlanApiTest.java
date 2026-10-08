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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주간 계획 get-or-create·조회 API 통합 테스트 (ST-B2-07). POST(신규 201·기존 200·weekEndDate=start+6)와
 * GET(주차별·요약·blocks·빈 응답·소유자)을 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class WeeklyPlanApiTest {

    private static final String PATH = "/api/v1/weekly-plans";
    private static final UUID MAIN = UUID.fromString("bbbb1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbb1111-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 7, 27); // 월요일

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
        // fixed_schedule_week_exceptions는 FK ON DELETE CASCADE로 fixed_schedules 삭제에 함께 지워진다.
        jdbc.update("DELETE FROM fixed_schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- POST 생성 ----------

    @Test
    @DisplayName("생성 → 201 · status=DRAFT · version=0 · total=0 · weekEndDate=start+6 · placedBlockCount=0")
    void createOk() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekStartDate\":\"2026-07-27\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.weeklyPlanId").exists())
                .andExpect(jsonPath("$.data.weekStartDate").value("2026-07-27"))
                .andExpect(jsonPath("$.data.weekEndDate").value("2026-08-02")) // +6일
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalPlannedMinutes").value(0))
                .andExpect(jsonPath("$.data.placedBlockCount").value(0))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.confirmedAt").doesNotExist());
    }

    @Test
    @DisplayName("같은 주차 재요청 → get-or-create: 200 + 기존 반환(같은 id, 오류 아님)")
    void duplicateWeekReturnsExisting() throws Exception {
        String firstId = com.jayway.jsonpath.JsonPath.read(
                create(MAIN, "2026-07-27").andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.data.weeklyPlanId");

        create(MAIN, "2026-07-27")
                .andExpect(status().isOk()) // 201 아님 — 기존 반환
                .andExpect(jsonPath("$.data.weeklyPlanId").value(firstId));
    }

    @Test
    @DisplayName("다른 사용자는 같은 주차 생성 가능(UNIQUE는 사용자별)")
    void sameWeekDifferentUserOk() throws Exception {
        create(MAIN, "2026-07-27").andExpect(status().isCreated());
        create(OTHER, "2026-07-27").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("weekStartDate 누락 → 400 E-COM-001")
    void weekStartDateRequired() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("동시 생성 요청 — 같은 사용자·같은 주차로 병렬 2건, 500 없이 201/200으로 수렴 (UNIQUE 경합 방어, 이슈#25)")
    void concurrentCreateSameWeekConvergesWithout500() throws Exception {
        String body = "{\"weekStartDate\":\"2026-07-27\"}";

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            // 두 스레드를 go 신호에 맞춰 같은 순간 출발시켜 경합 창을 넓힌다.
            java.util.concurrent.Future<Integer> f1 = pool.submit(() -> createStatus(body, ready, go));
            java.util.concurrent.Future<Integer> f2 = pool.submit(() -> createStatus(body, ready, go));
            ready.await();
            go.countDown();
            int s1 = f1.get();
            int s2 = f2.get();

            // 어느 쪽도 500이 아니어야 한다 — 하나는 201(신규), 다른 하나는 200(기존 반환)으로 수렴.
            assertThat(s1).isNotEqualTo(500);
            assertThat(s2).isNotEqualTo(500);
            assertThat(java.util.List.of(s1, s2)).containsExactlyInAnyOrder(200, 201);
        } finally {
            pool.shutdownNow();
        }

        // 계획은 정확히 1행(중복 생성 없음).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plans WHERE user_id = ? AND week_start_date = ?",
                Integer.class, MAIN, WEEK)).isEqualTo(1);
    }

    /** 두 스레드가 go 신호에 맞춰 동시에 POST하고 HTTP 상태를 돌려준다. */
    private int createStatus(String body, java.util.concurrent.CountDownLatch ready,
                             java.util.concurrent.CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    // ---------- GET 조회 ----------

    @Test
    @DisplayName("PLAN-01·02 주차별 조회 → 200 · 계획 + 요약")
    void getByWeek() throws Exception {
        create(MAIN, "2026-07-27").andExpect(status().isCreated());

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.weekStartDate").value("2026-07-27"))
                .andExpect(jsonPath("$.data.plan.weekEndDate").value("2026-08-02"))
                .andExpect(jsonPath("$.data.plan.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.plan.placedBlockCount").value(0));
    }

    @Test
    @DisplayName("조회에 blocks 목록 포함 — 블록 2건이 배열로·placedBlockCount=2 (캘린더 렌더링용)")
    void getIncludesBlocks() throws Exception {
        UUID planId = insertWeeklyPlan(MAIN, WEEK);
        UUID schedule = insertSchedule(MAIN, "고정일정");
        insertScheduleBlock(planId, schedule);
        insertScheduleBlock(planId, schedule);

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.placedBlockCount").value(2))
                .andExpect(jsonPath("$.data.blocks.length()").value(2))
                .andExpect(jsonPath("$.data.blocks[0].blockType").value("SCHEDULE"))
                .andExpect(jsonPath("$.data.blocks[0].scheduleId").value(schedule.toString()))
                .andExpect(jsonPath("$.data.blocks[0].title").value("고정일정")) // ③ 일정 제목 조인 파생
                .andExpect(jsonPath("$.data.blocks[0].projectId").doesNotExist()) // SCHEDULE은 projectId 없음
                .andExpect(jsonPath("$.data.blocks[0].status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("없는 주차 → 200 + data.plan=null · blocks 빈 목록 — 오류 아님(정본 WeeklyPlanView)")
    void emptyWhenNoPlan() throws Exception {
        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.plan").doesNotExist()) // null → FE는 data.plan으로 판단
                .andExpect(jsonPath("$.data.blocks.length()").value(0));
    }

    @Test
    @DisplayName("소유자 스코프 — 타인 주차는 내게 안 보임(200 + data.plan=null)")
    void ownerScope() throws Exception {
        create(OTHER, "2026-07-27").andExpect(status().isCreated());
        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.plan").doesNotExist());
    }

    @Test
    @DisplayName("weekStartDate 누락 → 400 (@ModelAttribute 검증)")
    void getRequiresWeekStartDate() throws Exception {
        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    // ---------- fixedSchedules (이슈 #90) ----------

    @Test
    @DisplayName("고정 일정 포함 — 예외·INACTIVE 없으면 activeThisWeek=true")
    void fixedSchedulesActiveByDefault() throws Exception {
        UUID fixedId = insertFixedSchedule(MAIN, "수업", "MON", "09:00", "10:00", null, null, "ACTIVE");

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(1))
                .andExpect(jsonPath("$.data.fixedSchedules[0].fixedScheduleId").value(fixedId.toString()))
                .andExpect(jsonPath("$.data.fixedSchedules[0].title").value("수업"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].weekday").value("MON"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].endTime").value("10:00:00"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].version").value(0))
                .andExpect(jsonPath("$.data.fixedSchedules[0].activeThisWeek").value(true));
    }

    @Test
    @DisplayName("그 주 week-exception 있으면 activeThisWeek=false, 다른 주는 true(PLAN-33)")
    void weekExceptionMakesInactiveOnlyThatWeek() throws Exception {
        UUID fixedId = insertFixedSchedule(MAIN, "수업", "MON", "09:00", "10:00", null, null, "ACTIVE");
        insertWeekException(fixedId, WEEK);

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(1))
                .andExpect(jsonPath("$.data.fixedSchedules[0].activeThisWeek").value(false));

        // 다음 주(예외 없음) — 같은 고정 일정이 다시 활성으로 보여야 한다.
        mockMvc.perform(get(PATH).param("weekStartDate", "2026-08-03").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(1))
                .andExpect(jsonPath("$.data.fixedSchedules[0].activeThisWeek").value(true));
    }

    @Test
    @DisplayName("status=INACTIVE면 예외가 없어도 activeThisWeek=false — 목록에선 제외하지 않는다(고스트 표시)")
    void inactiveStatusMakesActiveThisWeekFalseButListed() throws Exception {
        insertFixedSchedule(MAIN, "해지된 수업", "TUE", "09:00", "10:00", null, null, "INACTIVE");

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(1))
                .andExpect(jsonPath("$.data.fixedSchedules[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].activeThisWeek").value(false));
    }

    @Test
    @DisplayName("고정 일정 기간(startDate~endDate) 밖인 주는 목록에서 제외")
    void outOfDateRangeExcluded() throws Exception {
        // 9월 전체만 유효한 고정 일정 — 7/27 주는 겹치지 않아 제외돼야 한다.
        insertFixedSchedule(MAIN, "특강", "WED", "14:00", "16:00",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "ACTIVE");

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(0));

        // 겹치는 주(9/2 시작 ~ 9/8)에는 포함돼야 한다.
        mockMvc.perform(get(PATH).param("weekStartDate", "2026-08-31").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(1));
    }

    @Test
    @DisplayName("계획 없는 주(plan=null)에도 fixedSchedules는 채워진다 — 고정 일정은 계획 존재와 무관")
    void fixedSchedulesPresentEvenWithoutPlan() throws Exception {
        insertFixedSchedule(MAIN, "수업", "MON", "09:00", "10:00", null, null, "ACTIVE");

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan").doesNotExist()) // 계획 없음 — 오류 아님
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(1))
                .andExpect(jsonPath("$.data.fixedSchedules[0].activeThisWeek").value(true));
    }

    @Test
    @DisplayName("소유자 스코프 — 타인 고정 일정은 내 조회에 안 보임")
    void fixedSchedulesOwnerScope() throws Exception {
        insertFixedSchedule(OTHER, "타인 수업", "MON", "09:00", "10:00", null, null, "ACTIVE");

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(0));
    }

    @Test
    @DisplayName("후보 2건 — 하나는 그 주 예외 있음·하나는 없음 → IN 배치 조회가 각각 올바르게 가른다")
    void multipleCandidatesMixedExceptionAndNoException() throws Exception {
        UUID excepted = insertFixedSchedule(MAIN, "예외있음", "MON", "09:00", "10:00", null, null, "ACTIVE");
        UUID plain = insertFixedSchedule(MAIN, "예외없음", "TUE", "09:00", "10:00", null, null, "ACTIVE");
        insertWeekException(excepted, WEEK); // excepted만 이 주 예외

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(2))
                // weekday ASC(MON→TUE)라 excepted(MON)가 0번, plain(TUE)이 1번 — IN 배치 결과가 id별로 정확히 갈린다.
                .andExpect(jsonPath("$.data.fixedSchedules[0].fixedScheduleId").value(excepted.toString()))
                .andExpect(jsonPath("$.data.fixedSchedules[0].activeThisWeek").value(false))
                .andExpect(jsonPath("$.data.fixedSchedules[1].fixedScheduleId").value(plain.toString()))
                .andExpect(jsonPath("$.data.fixedSchedules[1].activeThisWeek").value(true));
    }

    @Test
    @DisplayName("요일 섞인 후보(FRI·MON·WED) → 달력순(MON→WED→FRI) 정렬, 문자열 알파벳순(FRI·MON·WED) 아님")
    void multipleCandidatesSortedCalendarOrderNotAlphabetical() throws Exception {
        // 삽입 순서를 알파벳순(FRI·MON·WED)과 다르게 섞어, 응답이 입력 순서나 알파벳 순이 아니라
        // 달력순으로 재정렬됐는지를 확인한다(리뷰 Should-fix — weekday가 VARCHAR라 SQL ORDER BY는 알파벳순).
        insertFixedSchedule(MAIN, "금요일", "FRI", "09:00", "10:00", null, null, "ACTIVE");
        insertFixedSchedule(MAIN, "월요일", "MON", "09:00", "10:00", null, null, "ACTIVE");
        insertFixedSchedule(MAIN, "수요일", "WED", "09:00", "10:00", null, null, "ACTIVE");

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedSchedules.length()").value(3))
                .andExpect(jsonPath("$.data.fixedSchedules[0].weekday").value("MON"))
                .andExpect(jsonPath("$.data.fixedSchedules[0].title").value("월요일"))
                .andExpect(jsonPath("$.data.fixedSchedules[1].weekday").value("WED"))
                .andExpect(jsonPath("$.data.fixedSchedules[1].title").value("수요일"))
                .andExpect(jsonPath("$.data.fixedSchedules[2].weekday").value("FRI"))
                .andExpect(jsonPath("$.data.fixedSchedules[2].title").value("금요일"));
    }

    // ---------- fixtures ----------

    private org.springframework.test.web.servlet.ResultActions create(UUID userId, String weekStartDate) throws Exception {
        return mockMvc.perform(post(PATH).header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weekStartDate\":\"" + weekStartDate + "\"}"));
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
                                          total_planned_minutes, status, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', 0, ?)
                """,
                id, userId, weekStart, weekStart.plusDays(6), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertSchedule(UUID userId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (schedule_id, user_id, title, start_at, end_at, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, ?)
                """,
                id, userId, title,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertScheduleBlock(UUID weeklyPlanId, UUID scheduleId) {
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, NULL, ?, 'SCHEDULE', ?, ?, 'SCHEDULED', ?)
                """,
                UUID.randomUUID(), weeklyPlanId, scheduleId,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }

    /** 고정 일정 직접 삽입(이슈 #90 테스트용) — source는 항상 MANUAL, status는 호출자가 ACTIVE/INACTIVE 지정. */
    private UUID insertFixedSchedule(UUID userId, String title, String weekday, String startTime, String endTime,
                                     LocalDate startDate, LocalDate endDate, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fixed_schedules (fixed_schedule_id, user_id, title, weekday, start_time, end_time,
                                             start_date, end_date, source, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, 0, ?)
                """,
                id, userId, title, weekday, LocalTime.parse(startTime), LocalTime.parse(endTime),
                startDate, endDate, status, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    /** 주차 한정 비활성화 예외 직접 삽입(PLAN-33) — FixedScheduleWeekExceptionRepository.insertIfAbsent와 동일 효과. */
    private void insertWeekException(UUID fixedScheduleId, LocalDate weekStartDate) {
        jdbc.update("""
                INSERT INTO fixed_schedule_week_exceptions (exception_id, fixed_schedule_id, week_start_date)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), fixedScheduleId, weekStartDate);
    }
}
