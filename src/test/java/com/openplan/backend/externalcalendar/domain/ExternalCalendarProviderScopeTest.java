package com.openplan.backend.externalcalendar.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구글 캘린더 연동이 요청하는 스코프 — 쓰는 엔드포인트마다 받는 스코프가 다르다.
 *
 * <p>🔴 calendar.events 는 events 엔드포인트만 덮는다. calendarList(가져올·내보낼 캘린더 선택)는
 * calendar.readonly · calendar · calendar.calendarlist · calendar.calendarlist.readonly 만 받는다.
 * readonly 에서 events 로 넓히며 목록 스코프가 빠져, 실계정 첫 연동에서 캘린더 선택이 403 으로
 * 막혔다(2026-10-08). 단위 테스트는 구글을 목으로 두므로 이 누락을 보지 못했다 — 그래서 문자열을 직접 잡는다.
 */
class ExternalCalendarProviderScopeTest {

    private static List<String> googleScopes() {
        return Arrays.asList(ExternalCalendarProvider.GOOGLE.calendarScope().split(" "));
    }

    @Test
    @DisplayName("🔴 캘린더 목록을 읽을 스코프가 있다 — calendar.events 만으로는 calendarList 가 403")
    void 목록_스코프가_있다() {
        assertThat(googleScopes()).contains("https://www.googleapis.com/auth/calendar.calendarlist.readonly");
    }

    @Test
    @DisplayName("일정을 쓸 스코프와 계정 확인 스코프가 그대로 있다")
    void 쓰기_신원_스코프가_있다() {
        assertThat(googleScopes()).contains(
                "https://www.googleapis.com/auth/calendar.events", "openid", "email");
    }

    @Test
    @DisplayName("부여받은 스코프로 쓰기 가능 판정이 그대로 선다 — 목록 스코프가 섞여도")
    void 새_스코프로도_쓸_수_있다() {
        ExternalCalendarConnection c = ExternalCalendarConnection.connect(UUID.randomUUID(),
                ExternalCalendarProvider.GOOGLE, "someone@example.com", "enc", "enc-r",
                Instant.parse("2030-01-01T00:00:00Z"),
                ExternalCalendarProvider.GOOGLE.calendarScope(),
                Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(c.canWrite()).isTrue();
    }
}
