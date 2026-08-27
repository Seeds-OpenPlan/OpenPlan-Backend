package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.global.config.AppProperties;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.JwtService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 캘린더 연동 인가 요청 조립·검증 (ONB-07 · FIX-14).
 *
 * <p><b>왜 서버가 조립하는가.</b> 구글은 {@code access_type=offline} 없이는 refresh 토큰을 주지 않는다.
 * 그것이 없으면 access 토큰이 만료되는 순간(약 1시간) 연동이 <b>영구히 죽고</b> 재연동 외 복구 수단이 없다.
 * 게다가 재연동해도 두 번째부터는 {@code prompt=consent} 없이 refresh 토큰을 주지 않아 같은 일이 반복된다.
 * 이 두 파라미터를 클라이언트가 기억하게 두면 언젠가 빠지고, 빠진 것은 <b>한 시간 뒤에야</b> 드러난다.
 *
 * <p><b>state 를 로그인과 섞지 않는다.</b> 같은 서명 키를 쓰되 주체를 {@code calendar:구글} 처럼 달리 둔다 —
 * 그렇게 하지 않으면 로그인 인가에서 받은 state 로 캘린더 연결을 성립시킬 수 있다.
 */
@Component
public class ExternalCalendarAuthorization {

    private static final String STATE_PREFIX = "calendar:";

    private final OAuthProperties oauthProperties;
    private final AppProperties appProperties;
    private final ObjectProvider<JwtService> jwtService;

    /**
     * @param jwtService dev-stub 프로파일에서는 이 빈이 없다(D-32) — 그때는 연동 자체가 불가능하므로
     *                   기동을 막지 않고 사용 시점에 실패시킨다.
     */
    public ExternalCalendarAuthorization(OAuthProperties oauthProperties, AppProperties appProperties,
                                         ObjectProvider<JwtService> jwtService) {
        this.oauthProperties = oauthProperties;
        this.appProperties = appProperties;
        this.jwtService = jwtService;
    }

    /** 제공자 인가 페이지 주소 — 302 의 Location 이 된다. */
    public String authorizationUrl(ExternalCalendarProvider provider, String redirectUri) {
        assertAllowedRedirect(redirectUri);

        OAuthProperties.Client client = oauthProperties.client(provider.oauthProvider())
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_EXT_001,
                        Map.of("provider", provider.name())));

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(provider.oauthProvider().authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", client.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", issueState(provider))
                // refresh 토큰을 받기 위한 두 파라미터 — 빠지면 연동이 한 시간 뒤에 죽는다.
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent");

        if (!provider.calendarScope().isBlank()) {
            builder.queryParam("scope", provider.calendarScope());
        }
        // build().encode() 여야 한다. build(true)는 "값이 이미 인코딩됨"을 뜻해 공백이 든 scope 에서 터진다.
        return builder.build().encode().toUriString();
    }

    /**
     * 콜백으로 돌아온 state 검증 — 서명이 유효하고 <b>이 제공자의 캘린더 인가로 발급된 것</b>이어야 한다.
     * 로그인 state 나 다른 제공자의 state 를 가져다 붙이는 교차 사용을 막는다.
     */
    public void verifyState(ExternalCalendarProvider provider, String state) {
        String signedFor = requireJwt().parseOAuthState(state)
                .orElseThrow(() -> invalidState("서명 검증 실패"));

        if (!expectedStateSubject(provider).equals(signedFor)) {
            throw invalidState("발급 대상 불일치");
        }
    }

    private String issueState(ExternalCalendarProvider provider) {
        return requireJwt().issueOAuthState(expectedStateSubject(provider));
    }

    private static String expectedStateSubject(ExternalCalendarProvider provider) {
        return STATE_PREFIX + provider.oauthProvider().pathValue();
    }

    /**
     * 열린 리다이렉트 차단 — 인가 코드가 우리 프론트가 아닌 곳으로 배달되면 그 코드로 남의 캘린더가
     * 연결된다. 제공자 콘솔에도 등록돼 있어야 하지만, 서버가 먼저 거른다.
     */
    private void assertAllowedRedirect(String redirectUri) {
        String allowedPrefix = appProperties.baseUrl();
        if (redirectUri == null || allowedPrefix.isBlank() || !redirectUri.startsWith(allowedPrefix)) {
            throw new OpenPlanException(ErrorCode.E_COM_009,
                    Map.of("redirectUri", String.valueOf(redirectUri)),
                    "허용되지 않은 redirectUri 입니다");
        }
    }

    private JwtService requireJwt() {
        JwtService service = jwtService.getIfAvailable();
        if (service == null) {
            // dev-stub 로 도는 로컬 — 인가 자체가 성립하지 않는다.
            throw new OpenPlanException(ErrorCode.E_EXT_001, Map.of("reason", "jwt-unavailable"));
        }
        return service;
    }

    private static OpenPlanException invalidState(String reason) {
        return new OpenPlanException(ErrorCode.E_COM_009, Map.of("state", reason),
                "인가 요청을 확인할 수 없습니다");
    }
}
