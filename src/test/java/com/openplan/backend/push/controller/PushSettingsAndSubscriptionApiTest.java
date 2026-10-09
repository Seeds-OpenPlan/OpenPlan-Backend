package com.openplan.backend.push.controller;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 푸시 설정·구독·VAPID 공개키 API 통합 테스트 (ADR-0015). 발송기 자체({@code PushReminderDispatcher})는
 * 별도({@code PushReminderDispatcherTest}) — 이 테스트는 계약(기본값·검증·소유자 스코프)만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PushSettingsAndSubscriptionApiTest {

    private static final UUID MAIN = UUID.fromString("ffff0015-0000-0000-0000-000000000011");
    private static final UUID OTHER = UUID.fromString("ffff0015-0000-0000-0000-000000000012");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM push_settings WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------------------------------------------------------------- 설정

    @Test
    @DisplayName("행이 없으면 3토글 전부 false")
    void defaultAllOff() throws Exception {
        mockMvc.perform(as(get("/api/v1/users/me/push-settings"), MAIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskEnabled").value(false))
                .andExpect(jsonPath("$.data.fixedScheduleEnabled").value(false))
                .andExpect(jsonPath("$.data.scheduleEnabled").value(false));
    }

    @Test
    @DisplayName("PUT 저장 → 그대로 반영되고 재조회해도 유지된다(upsert)")
    void saveThenReadBack() throws Exception {
        mockMvc.perform(as(put("/api/v1/users/me/push-settings"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskEnabled":true,"fixedScheduleEnabled":false,"scheduleEnabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskEnabled").value(true))
                .andExpect(jsonPath("$.data.scheduleEnabled").value(true));

        mockMvc.perform(as(get("/api/v1/users/me/push-settings"), MAIN))
                .andExpect(jsonPath("$.data.taskEnabled").value(true))
                .andExpect(jsonPath("$.data.fixedScheduleEnabled").value(false))
                .andExpect(jsonPath("$.data.scheduleEnabled").value(true));
    }

    // ---------------------------------------------------------------- VAPID 공개키

    @Test
    @DisplayName("VAPID 키가 설정되지 않은 환경(테스트 기본)에서는 publicKey가 null")
    void vapidPublicKeyNullWhenUnconfigured() throws Exception {
        mockMvc.perform(as(get("/api/v1/push/vapid-public-key"), MAIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicKey").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------------------------------------------------------------- 구독

    @Test
    @DisplayName("구독 등록 — platform!=ANDROID_APP 은 422 E-COM-009")
    void subscribeRejectsNonAndroidPlatform() throws Exception {
        mockMvc.perform(as(post("/api/v1/users/me/push-subscriptions"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"https://push.example.net/ep-1",
                                 "keys":{"p256dh":"p1","auth":"a1"},"platform":"WEB"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    @Test
    @DisplayName("구독 등록 — ANDROID_APP 이면 200, 같은 endpoint 재등록은 재할당(소유자 갈아끼움)")
    void subscribeUpsertsByEndpoint() throws Exception {
        String endpoint = "https://push.example.net/ep-shared";
        mockMvc.perform(as(post("/api/v1/users/me/push-subscriptions"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"%s","keys":{"p256dh":"p1","auth":"a1"},"platform":"ANDROID_APP"}"""
                                .formatted(endpoint)))
                .andExpect(status().isOk());

        Integer ownerCountMain = jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE endpoint = ? AND user_id = ?",
                Integer.class, endpoint, MAIN);
        org.junit.jupiter.api.Assertions.assertEquals(1, ownerCountMain);

        // 같은 기기가 OTHER 계정으로 다시 구독 — 소유자가 갈아끼워진다(기기 1대 = 1행 유지).
        mockMvc.perform(as(post("/api/v1/users/me/push-subscriptions"), OTHER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"%s","keys":{"p256dh":"p2","auth":"a2"},"platform":"ANDROID_APP"}"""
                                .formatted(endpoint)))
                .andExpect(status().isOk());

        Integer totalRows = jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE endpoint = ?", Integer.class, endpoint);
        org.junit.jupiter.api.Assertions.assertEquals(1, totalRows);
        String owner = jdbc.queryForObject(
                "SELECT user_id FROM push_subscriptions WHERE endpoint = ?", String.class, endpoint);
        org.junit.jupiter.api.Assertions.assertEquals(OTHER.toString(), owner);
    }

    @Test
    @DisplayName("구독 해지 — 본인 소유만 지운다. 남의 구독을 지정하면 204여도 삭제되지 않는다")
    void unsubscribeOnlyDeletesOwnSubscription() throws Exception {
        String endpoint = "https://push.example.net/ep-owned-by-main";
        jdbc.update("""
                INSERT INTO push_subscriptions (push_subscription_id, user_id, endpoint, p256dh, auth, platform)
                VALUES (?, ?, ?, 'p', 'a', 'ANDROID_APP')
                """, UUID.randomUUID(), MAIN, endpoint);

        // OTHER가 MAIN의 endpoint를 지정해 해지 요청 — 204지만(존재 은닉) 실제로는 삭제되지 않는다.
        mockMvc.perform(as(delete("/api/v1/users/me/push-subscriptions"), OTHER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + endpoint + "\"}"))
                .andExpect(status().isNoContent());
        Integer stillThere = jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE endpoint = ?", Integer.class, endpoint);
        org.junit.jupiter.api.Assertions.assertEquals(1, stillThere);

        // MAIN 본인이 해지하면 실제로 지워진다.
        mockMvc.perform(as(delete("/api/v1/users/me/push-subscriptions"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + endpoint + "\"}"))
                .andExpect(status().isNoContent());
        Integer gone = jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE endpoint = ?", Integer.class, endpoint);
        org.junit.jupiter.api.Assertions.assertEquals(0, gone);
    }

    @Test
    @DisplayName("구독 해지 — 부재 endpoint 도 204(멱등)")
    void unsubscribeMissingEndpointIsStillNoContent() throws Exception {
        mockMvc.perform(as(delete("/api/v1/users/me/push-subscriptions"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example.net/never-existed\"}"))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- 픽스처

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UUID userId) {
        return b.header("X-Dev-User", userId.toString());
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
