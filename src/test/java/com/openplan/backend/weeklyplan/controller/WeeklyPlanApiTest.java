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
                .andExpect(jsonPath("$.data.blocks.length()").value(0)) // 신규 계획 → 빈 목록
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

    // ---------- GET 조회 ----------

    @Test
    @DisplayName("PLAN-01·02 주차별 조회 → 200 · 계획 + 요약")
    void getByWeek() throws Exception {
        create(MAIN, "2026-07-27").andExpect(status().isCreated());

        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekStartDate").value("2026-07-27"))
                .andExpect(jsonPath("$.data.weekEndDate").value("2026-08-02"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.placedBlockCount").value(0));
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
                .andExpect(jsonPath("$.data.placedBlockCount").value(2))
                .andExpect(jsonPath("$.data.blocks.length()").value(2))
                .andExpect(jsonPath("$.data.blocks[0].blockType").value("SCHEDULE"))
                .andExpect(jsonPath("$.data.blocks[0].scheduleId").value(schedule.toString()))
                .andExpect(jsonPath("$.data.blocks[0].title").value("고정일정")) // ③ 일정 제목 조인 파생
                .andExpect(jsonPath("$.data.blocks[0].projectId").doesNotExist()) // SCHEDULE은 projectId 없음
                .andExpect(jsonPath("$.data.blocks[0].status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("없는 주차 → 200 + 빈 응답(data 없음) — 오류 아님")
    void emptyWhenNoPlan() throws Exception {
        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("소유자 스코프 — 타인 주차는 내게 안 보임(200 + 빈 응답)")
    void ownerScope() throws Exception {
        create(OTHER, "2026-07-27").andExpect(status().isCreated());
        mockMvc.perform(get(PATH).param("weekStartDate", "2026-07-27").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("weekStartDate 누락 → 400 (@ModelAttribute 검증)")
    void getRequiresWeekStartDate() throws Exception {
        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
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
}
