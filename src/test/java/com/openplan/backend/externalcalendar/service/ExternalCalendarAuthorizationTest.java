package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.global.config.AppProperties;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.JwtProperties;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.common.Weekday;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 캘린더 인가 요청 조립·검증 (ONB-07 · FIX-14). 순수 단위 — DB·Docker 불요.
 *
 * <p>검증의 축은 셋이다: ⑴ refresh 토큰을 받기 위한 파라미터가 <b>반드시</b> 실리는가
 * ⑵ 로그인 state 와 <b>교차 사용이 불가능한가</b> ⑶ 인가 코드가 우리 프론트 밖으로 배달되지 않는가.
 */
class ExternalCalendarAuthorizationTest {

    private static final String FRONTEND = "http://localhost:5173";

    private final UserClock clock = new UserClock() {
        @Override
        public Instant now() {
            return Instant.parse("2026-08-19T00:00:00Z");
        }

        @Override
        public LocalDate todayOf(UUID userId) {
            return LocalDate.of(2026, 8, 19);
        }

        @Override
        public ZoneId zoneOf(UUID userId) {
            return ZoneId.of("Asia/Seoul");
        }

        // #37 이 UserClock 에 추가한 메서드 — 이 스텁은 그 이전에 쓰였다. 외부 캘린더는
        // 주 시작 요일을 쓰지 않으므로 기본값(MON)으로 둔다.
        @Override
        public Weekday weekStartDayOf(UUID userId) {
            return Weekday.MON;
        }
    };

    private final JwtService jwtService = new JwtService(
            new JwtProperties("test-only-secret-value-that-is-long-enough-32",
                    Duration.ofMinutes(30), Duration.ofDays(14), false, "openplan-test"),
            clock);

    private final ExternalCalendarAuthorization authorization = new ExternalCalendarAuthorization(
            new OAuthProperties(Map.of("google",
                    new OAuthProperties.Client("google-client", "google-secret"))),
            new AppProperties(FRONTEND, "http://localhost:8080"),
            provider(jwtService));

    @Test
    @DisplayName("인가 URL 에 access_type=offline 과 prompt=consent 가 실린다 — 없으면 한 시간 뒤 연동이 죽는다")
    void includesOfflineAccessParameters() {
        String url = authorization.authorizationUrl(ExternalCalendarProvider.GOOGLE, FRONTEND + "/settings/calendar");

        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
    }

    @Test
    @DisplayName("로그인 scope 가 아니라 캘린더 읽기 scope 를 요구한다")
    void requestsCalendarScope() {
        String url = authorization.authorizationUrl(ExternalCalendarProvider.GOOGLE, FRONTEND + "/settings/calendar");

        assertThat(url).contains("calendar.readonly");
    }

    @Test
    @DisplayName("발급한 state 는 그대로 검증을 통과한다")
    void issuedStateVerifies() {
        String url = authorization.authorizationUrl(ExternalCalendarProvider.GOOGLE, FRONTEND + "/cb");
        String state = queryParam(url, "state");

        assertThatCode(() -> authorization.verifyState(ExternalCalendarProvider.GOOGLE, state))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("로그인 인가의 state 로는 캘린더를 연결할 수 없다 — 주체가 다르다")
    void loginStateIsRejected() {
        String loginState = jwtService.issueOAuthState("google");   // 로그인이 발급하는 형태

        assertThatThrownBy(() -> authorization.verifyState(ExternalCalendarProvider.GOOGLE, loginState))
                .isInstanceOf(OpenPlanException.class);
    }

    @Test
    @DisplayName("위조된 state 는 거부된다")
    void forgedStateIsRejected() {
        assertThatThrownBy(() -> authorization.verifyState(ExternalCalendarProvider.GOOGLE, "not-a-jwt"))
                .isInstanceOf(OpenPlanException.class);
    }

    @Test
    @DisplayName("우리 프론트 밖 redirectUri 는 거부된다 — 인가 코드가 남의 도메인으로 배달되면 안 된다")
    void rejectsForeignRedirectUri() {
        assertThatThrownBy(() -> authorization.authorizationUrl(
                ExternalCalendarProvider.GOOGLE, "https://evil.example.com/steal"))
                .isInstanceOf(OpenPlanException.class)
                .hasMessageContaining("redirectUri");
    }

    private static String queryParam(String url, String name) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("파라미터 없음: " + name);
    }

    /** {@link ObjectProvider} 의 필요한 메서드 하나만 채운 최소 구현. */
    private static ObjectProvider<JwtService> provider(JwtService service) {
        return new ObjectProvider<>() {
            @Override
            public JwtService getIfAvailable() {
                return service;
            }

            @Override
            public JwtService getObject() {
                return service;
            }

            @Override
            public JwtService getObject(Object... args) {
                return service;
            }

            @Override
            public JwtService getIfUnique() {
                return service;
            }
        };
    }
}
