package com.openplan.backend.global.security;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * 인증 쿠키 조립 (openapi {@code /auth/sessions} — "Set-Cookie op_at/op_rt").
 *
 * <p><b>httpOnly 는 타협하지 않는다.</b> 토큰을 JS가 읽을 수 있으면 XSS 하나로 세션이 통째로 나간다
 * (보안 요구 "토큰 저장 httpOnly"). 그래서 프론트는 토큰을 <b>보지 못하며</b>, 로그인 여부는
 * {@code GET /auth/session} 응답으로만 판단한다 — 오민아에게 이 점을 알려야 한다.
 *
 * <p><b>경로가 다르다.</b> {@code op_at}은 모든 API에 실려야 하므로 {@code /api/v1},
 * {@code op_rt}는 <b>갱신 엔드포인트에만</b> 실리면 되므로 {@code /api/v1/auth/token-refresh}로 좁힌다.
 * 좁히면 일반 요청에 refresh 가 흘러다니지 않아 노출면이 준다.
 *
 * <p><b>SameSite=Lax</b> — 단일 오리진(D-11, Nginx가 프론트와 API를 같이 서빙)이라 Strict 도 가능하지만,
 * OAuth 콜백이 외부 제공자에서 <b>리다이렉트로 되돌아오는</b> 흐름(W4 3단계)에서 Strict 는 쿠키를 끊는다.
 */
public class AuthCookies {

    public static final String ACCESS = "op_at";
    public static final String REFRESH = "op_rt";

    private static final String ACCESS_PATH = "/api/v1";
    private static final String REFRESH_PATH = "/api/v1/auth/token-refresh";

    private final JwtProperties props;

    public AuthCookies(JwtProperties props) {
        this.props = props;
    }

    public ResponseCookie access(String token) {
        return base(ACCESS, token, ACCESS_PATH, props.accessTtl());
    }

    public ResponseCookie refresh(String token) {
        return base(REFRESH, token, REFRESH_PATH, props.refreshTtl());
    }

    /** 로그아웃·refresh 무효 시. 값을 비우고 Max-Age=0 으로 즉시 만료시킨다. */
    public ResponseCookie clearAccess() {
        return base(ACCESS, "", ACCESS_PATH, Duration.ZERO);
    }

    public ResponseCookie clearRefresh() {
        return base(REFRESH, "", REFRESH_PATH, Duration.ZERO);
    }

    private ResponseCookie base(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.cookieSecure())   // localhost(http)=false → W5 배포 시 true
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
