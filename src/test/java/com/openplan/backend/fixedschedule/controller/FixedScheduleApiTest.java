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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고정 일정 생성·목록 API 통합 테스트 (FIX-05·FIX-04). MANUAL 생성(201·source/status·5분 단위·시작<종료)과
 * 목록(정렬·status 필터·소유자 스코프)을 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class FixedScheduleApiTest {

    private static final String PATH = "/api/v1/fixed-schedules";
    private static final UUID MAIN = UUID.fromString("cccc1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccc1111-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM fixed_schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- POST 생성 ----------

    @Test
    @DisplayName("생성 → 201 · source=MANUAL · status=ACTIVE · version=0 · 입력 반영")
    void createOk() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:00","endTime":"10:30"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fixedScheduleId").exists())
                .andExpect(jsonPath("$.data.title").value("수업"))
                .andExpect(jsonPath("$.data.weekday").value("MON"))
                .andExpect(jsonPath("$.data.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("10:30:00"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("기간 한정(startDate·endDate) 포함 생성 → 201 · 날짜 반영")
    void createWithDateRange() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강","weekday":"WED","startTime":"14:00","endTime":"16:00",
                                 "startDate":"2026-09-01","endDate":"2026-11-30"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-11-30"));
    }

    @Test
    @DisplayName("제목 누락 → 422 E-COM-009 (title.required)")
    void titleRequired() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekday":"MON","startTime":"09:00","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("title"));
    }

    @Test
    @DisplayName("미정의 요일 → 422 E-COM-009 (weekday.invalid, 500 아님)")
    void invalidWeekday() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MONDAY","startTime":"09:00","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("weekday"));
    }

    @Test
    @DisplayName("5분 단위 아님 → 422 E-COM-009 (startTime.step)")
    void notFiveMinuteStep() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:03","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("startTime"));
    }

    @Test
    @DisplayName("시작 >= 종료 → 422 E-COM-009 (endTime.range)")
    void startNotBeforeEnd() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"10:00","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("endTime"));
    }

    // ---------- PATCH 편집 ----------

    @Test
    @DisplayName("편집 → 200 · 필드 교체 · version 증가(0→1)")
    void updateOk() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업(변경)","weekday":"TUE","startTime":"11:00","endTime":"12:30","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수업(변경)"))
                .andExpect(jsonPath("$.data.weekday").value("TUE"))
                .andExpect(jsonPath("$.data.startTime").value("11:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("12:30:00"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("전체 교체 — 생략한 기간(startDate/endDate)은 null로 교체됨")
    void updateReplacesDateRangeWithNull() throws Exception {
        String body = mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강","weekday":"WED","startTime":"14:00","endTime":"16:00",
                                 "startDate":"2026-09-01","endDate":"2026-11-30"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.fixedScheduleId"));

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강","weekday":"WED","startTime":"14:00","endTime":"16:00","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").doesNotExist())
                .andExpect(jsonPath("$.data.endDate").doesNotExist());
    }

    @Test
    @DisplayName("없는 id 편집 → 404 E-COM-004")
    void updateNotFound() throws Exception {
        mockMvc.perform(patch(PATH + "/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","weekday":"MON","startTime":"09:00","endTime":"10:00","version":0}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 고정 일정 편집 → 404 (존재 은닉)")
    void updateOtherUserHidden() throws Exception {
        UUID id = create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","weekday":"MON","startTime":"09:00","endTime":"10:00","version":0}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("version 불일치 → 409 E-COM-006 (latest 동봉)")
    void updateVersionConflict() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:00","endTime":"10:00","version":5}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"))
                .andExpect(jsonPath("$.error.details.latest.fixedScheduleId").value(id.toString()))
                .andExpect(jsonPath("$.error.details.latest.version").value(0));
    }

    @Test
    @DisplayName("version 누락 → 400 (낙관락 입력 필수)")
    void updateVersionRequired() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:00","endTime":"10:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("편집 값 오류(5분 단위 아님) → 422 E-COM-009")
    void updateInvalidField() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:03","endTime":"10:00","version":0}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("startTime"));
    }

    // ---------- GET 목록 ----------

    @Test
    @DisplayName("목록 → weekday·시작 시각 순 정렬")
    void listOrdered() throws Exception {
        create(MAIN, "회의", "WED", "09:00", "10:00");
        create(MAIN, "근무", "MON", "13:00", "14:00");
        create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].title").value("수업"))   // MON 09:00
                .andExpect(jsonPath("$.data[1].title").value("근무"))   // MON 13:00
                .andExpect(jsonPath("$.data[2].title").value("회의"));  // WED 09:00
    }

    @Test
    @DisplayName("목록 status=ACTIVE 필터 — INACTIVE는 제외")
    void listStatusFilter() throws Exception {
        create(MAIN, "활성", "MON", "09:00", "10:00");
        UUID inactive = create(MAIN, "비활성", "TUE", "09:00", "10:00");
        jdbc.update("UPDATE fixed_schedules SET status = 'INACTIVE' WHERE fixed_schedule_id = ?", inactive);

        mockMvc.perform(get(PATH).param("status", "ACTIVE").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("활성"));
    }

    @Test
    @DisplayName("소유자 스코프 — 타인 고정 일정은 내게 안 보임(빈 목록)")
    void ownerScope() throws Exception {
        create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- fixtures ----------

    private UUID create(UUID userId, String title, String weekday, String start, String end) throws Exception {
        String body = mockMvc.perform(post(PATH).header("X-Dev-User", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"weekday\":\"" + weekday
                                + "\",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.fixedScheduleId"));
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
}
