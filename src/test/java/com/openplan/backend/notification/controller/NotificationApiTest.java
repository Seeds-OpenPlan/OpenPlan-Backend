package com.openplan.backend.notification.controller;

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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 5 EP 통합 테스트 (ST-B1-12 · NOTI-01~04).
 *
 * <p>고정하는 것: <b>설정 지연 시드</b>(최초 조회 시 5행·기본 켜짐) · 부분 저장이 나머지 유형을
 * 건드리지 않음 · 알 수 없는 유형 422 · 미읽음 수 meta · <b>읽음 멱등</b>(재요청이 readAt 을 덮지 않음) ·
 * 타인 알림 404 은닉 · 전체 읽음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class NotificationApiTest {

    private static final UUID MAIN = UUID.fromString("eeee0012-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeee0012-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM notifications WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM notification_settings WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ------------------------------------------------------------------ 설정

    @Test
    @DisplayName("설정 최초 조회 → 5유형이 기본 켜짐으로 지연 시드된다")
    void settingsAreLazilySeededOnFirstRead() throws Exception {
        mockMvc.perform(as(get("/api/v1/users/me/notification-settings"), MAIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].notificationType").value("DEADLINE_SOON"))
                .andExpect(jsonPath("$.data[0].isEnabled").value(true))
                .andExpect(jsonPath("$.data[4].notificationType").value("SUPPORT_ANSWERED"));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notification_settings WHERE user_id = ?", Integer.class, MAIN);
        org.junit.jupiter.api.Assertions.assertEquals(5, rows);
    }

    @Test
    @DisplayName("설정 재조회는 시드를 다시 만들지 않는다 — 5행 유지(멱등)")
    void settingsSeedIsIdempotent() throws Exception {
        mockMvc.perform(as(get("/api/v1/users/me/notification-settings"), MAIN)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/users/me/notification-settings"), MAIN)).andExpect(status().isOk());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notification_settings WHERE user_id = ?", Integer.class, MAIN);
        org.junit.jupiter.api.Assertions.assertEquals(5, rows);
    }

    @Test
    @DisplayName("부분 저장 — 보낸 유형만 꺼지고 나머지 4종은 켜진 채로 남는다")
    void partialSaveLeavesOtherTypesUntouched() throws Exception {
        mockMvc.perform(as(put("/api/v1/users/me/notification-settings"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":[{\"notificationType\":\"RETROSPECT\",\"isEnabled\":false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));

        Boolean retrospect = jdbc.queryForObject(
                "SELECT is_enabled FROM notification_settings WHERE user_id = ? AND notification_type = 'RETROSPECT'",
                Boolean.class, MAIN);
        Integer enabled = jdbc.queryForObject(
                "SELECT count(*) FROM notification_settings WHERE user_id = ? AND is_enabled", Integer.class, MAIN);
        org.junit.jupiter.api.Assertions.assertEquals(false, retrospect);
        org.junit.jupiter.api.Assertions.assertEquals(4, enabled); // 나머지는 그대로 켜짐
    }

    @Test
    @DisplayName("알 수 없는 유형 → 422 E-COM-009 (조용히 무시하면 껐다고 믿는 알림이 계속 온다)")
    void unknownTypeIsRejected() throws Exception {
        mockMvc.perform(as(put("/api/v1/users/me/notification-settings"), MAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":[{\"notificationType\":\"NOPE\",\"isEnabled\":false}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ------------------------------------------------------------------ 센터

    @Test
    @DisplayName("알림 0건 → 빈 배열 + meta.unreadCount=0")
    void emptyCenter() throws Exception {
        mockMvc.perform(as(get("/api/v1/notifications"), MAIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.unreadCount").value(0))
                .andExpect(jsonPath("$.meta.page.number").value(1));
    }

    @Test
    @DisplayName("목록은 최신순 + 미읽음 수를 meta 에 싣는다")
    void listIsNewestFirstWithUnreadCount() throws Exception {
        insertNotification(MAIN, "TODAY_TASKS", "오래된 알림", "/weekly", "2026-08-01T00:00:00Z");
        insertNotification(MAIN, "RETROSPECT", "최근 알림", "/statistics", "2026-08-10T00:00:00Z");

        mockMvc.perform(as(get("/api/v1/notifications"), MAIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("최근 알림"))
                .andExpect(jsonPath("$.data[0].routePath").value("/statistics"))
                .andExpect(jsonPath("$.data[1].title").value("오래된 알림"))
                .andExpect(jsonPath("$.meta.unreadCount").value(2));
    }

    @Test
    @DisplayName("읽음 처리는 멱등 — 두 번째 요청도 200이고 readAt 을 덮어쓰지 않는다")
    void markReadIsIdempotent() throws Exception {
        UUID id = insertNotification(MAIN, "TODAY_TASKS", "알림", "/weekly", "2026-08-01T00:00:00Z");

        mockMvc.perform(as(patch("/api/v1/notifications/" + id + "/read"), MAIN)).andExpect(status().isOk());
        OffsetDateTime first = jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE notification_id = ?", OffsetDateTime.class, id);

        mockMvc.perform(as(patch("/api/v1/notifications/" + id + "/read"), MAIN)).andExpect(status().isOk());
        OffsetDateTime second = jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE notification_id = ?", OffsetDateTime.class, id);

        org.junit.jupiter.api.Assertions.assertNotNull(first);
        org.junit.jupiter.api.Assertions.assertEquals(first, second); // 최초 읽은 시각이 사실이다
    }

    @Test
    @DisplayName("타인 알림 읽음 → 404 (존재 은닉)")
    void markReadOnOthersNotification() throws Exception {
        UUID id = insertNotification(OTHER, "TODAY_TASKS", "남의 알림", "/weekly", "2026-08-01T00:00:00Z");

        mockMvc.perform(as(patch("/api/v1/notifications/" + id + "/read"), MAIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("전체 읽음 → 미읽음 0. 남의 알림은 건드리지 않는다")
    void markAllReadScopedToOwner() throws Exception {
        insertNotification(MAIN, "TODAY_TASKS", "내 알림1", "/weekly", "2026-08-01T00:00:00Z");
        insertNotification(MAIN, "RETROSPECT", "내 알림2", "/statistics", "2026-08-02T00:00:00Z");
        insertNotification(OTHER, "TODAY_TASKS", "남의 알림", "/weekly", "2026-08-01T00:00:00Z");

        mockMvc.perform(as(patch("/api/v1/notifications/read-all"), MAIN)).andExpect(status().isOk());

        Integer mineUnread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, MAIN);
        Integer othersUnread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, OTHER);
        org.junit.jupiter.api.Assertions.assertEquals(0, mineUnread);
        org.junit.jupiter.api.Assertions.assertEquals(1, othersUnread);
    }

    @Test
    @DisplayName("미읽음 0건이어도 전체 읽음은 200")
    void markAllReadWithNothingUnread() throws Exception {
        mockMvc.perform(as(patch("/api/v1/notifications/read-all"), MAIN)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 픽스처

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID userId) {
        return builder.header("X-Dev-User", userId.toString());
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

    /** 설정 행이 FK NOT NULL 이라 알림보다 먼저 만든다 — 서비스의 지연 시드와 같은 순서다. */
    private UUID insertNotification(UUID userId, String type, String title, String routePath, String createdAt) {
        UUID settingId = jdbc.query("""
                INSERT INTO notification_settings (notification_setting_id, user_id, notification_type, is_enabled)
                VALUES (?, ?, ?, true) ON CONFLICT (user_id, notification_type) DO NOTHING
                RETURNING notification_setting_id
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, UUID.randomUUID(), userId, type);
        if (settingId == null) {
            settingId = jdbc.queryForObject(
                    "SELECT notification_setting_id FROM notification_settings WHERE user_id = ? AND notification_type = ?",
                    UUID.class, userId, type);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (notification_id, user_id, notification_setting_id, notification_type,
                                           title, route_path, read_at, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, true, ?)
                """, id, userId, settingId, type, title, routePath, OffsetDateTime.parse(createdAt));
        return id;
    }
}
