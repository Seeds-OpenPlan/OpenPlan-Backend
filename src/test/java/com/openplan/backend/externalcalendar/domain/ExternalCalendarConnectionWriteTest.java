package com.openplan.backend.externalcalendar.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「이 연동으로 밖에 쓸 수 있는가」 (#69 1단계).
 *
 * <p>🔴 이 판정이 틀리면 <b>403 만 쌓이거나, 더 나쁘게는 남의 캘린더를 건드린다.</b> 스코프를
 * 넓히기 전에 연동한 사용자는 옛 권한의 토큰을 들고 있고, 요청한 스코프로 판단하면 그 차이를
 * 영영 볼 수 없다.
 */
class ExternalCalendarConnectionWriteTest {

    private static final String WRITE = "openid email https://www.googleapis.com/auth/calendar.events";
    private static final String READ_ONLY = "openid email https://www.googleapis.com/auth/calendar.readonly";

    private static ExternalCalendarConnection google(String grantedScope) {
        return ExternalCalendarConnection.connect(UUID.randomUUID(), ExternalCalendarProvider.GOOGLE,
                "someone@example.com", "enc", "enc-r", Instant.parse("2030-01-01T00:00:00Z"),
                grantedScope, Instant.parse("2026-09-17T00:00:00Z"));
    }

    private static ExternalCalendarConnection apple() {
        return ExternalCalendarConnection.connect(UUID.randomUUID(), ExternalCalendarProvider.APPLE,
                "someone@icloud.com", "enc", null, null, null, Instant.parse("2026-09-17T00:00:00Z"));
    }

    @Test
    @DisplayName("쓰기 스코프를 부여받았으면 쓸 수 있다")
    void 쓰기_스코프가_있으면_쓴다() {
        assertThat(google(WRITE).canWrite()).isTrue();
    }

    @Test
    @DisplayName("🔴 스코프를 넓히기 전에 연동한 행 — granted_scope 가 NULL 이면 쓰지 않는다")
    void 모르면_쓰지_않는다() {
        // 마이그레이션 직후 기존 행이 정확히 이 모양이다. 토큰은 살아 있고 연동도 ACTIVE 지만
        // 그 토큰은 calendar.readonly 로 발급됐다 — 쓰면 구글이 403 을 준다.
        assertThat(google(null).canWrite()).isFalse();
        assertThat(google("").canWrite()).isFalse();
    }

    @Test
    @DisplayName("🔴 읽기 권한만 부여받았으면 쓰지 않는다 — 사용자가 동의 화면에서 일부만 허용할 수 있다")
    void 읽기_권한만이면_쓰지_않는다() {
        assertThat(google(READ_ONLY).canWrite()).isFalse();
    }

    @Test
    @DisplayName("비활성 연동으로는 쓰지 않는다 — 권한이 있어도 사용자가 꺼 둔 것이다")
    void 비활성이면_쓰지_않는다() {
        ExternalCalendarConnection c = google(WRITE);
        c.changeStatus(ConnectionStatus.INACTIVE);
        assertThat(c.canWrite()).isFalse();
    }

    @Test
    @DisplayName("애플은 스코프가 없다 — 앱 암호가 곧 전권이라 붙을 수 있으면 쓸 수 있다")
    void 애플은_스코프를_보지_않는다() {
        assertThat(apple().canWrite()).isTrue();
    }

    @Test
    @DisplayName("🔴 갱신 응답에 스코프가 없으면 기존 값을 지우지 않는다 — 쓸 수 있던 연동이 모름으로 돌아간다")
    void 빈_갱신은_기존_스코프를_지우지_않는다() {
        ExternalCalendarConnection c = google(WRITE);
        c.updateGrantedScope(null);
        c.updateGrantedScope("   ");
        assertThat(c.canWrite()).as("제공자가 scope 를 생략해도 쓰기 가능은 유지된다").isTrue();
    }

    @Test
    @DisplayName("권한이 회수되면 갱신이 그것을 반영한다")
    void 회수된_권한은_갱신에서_반영된다() {
        ExternalCalendarConnection c = google(WRITE);
        c.updateGrantedScope(READ_ONLY);   // 사용자가 구글 계정에서 캘린더 쓰기를 뺐다
        assertThat(c.canWrite()).isFalse();
    }
}
