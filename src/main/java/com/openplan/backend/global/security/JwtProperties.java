package com.openplan.backend.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 쿠키 인증 설정 (D-16 4주차 실구현 · ADR-0010).
 *
 * <p>비밀키는 코드·저장소에 두지 않는다 — {@code .env}의 {@code JWT_SECRET}만 읽는다
 * (be1-notes "시크릿 관리"). HS256이므로 키는 최소 32바이트여야 하며, 짧으면 기동 시 실패시킨다.
 * 조용히 약한 키로 뜨는 것보다 못 뜨는 편이 낫다.
 *
 * <p><b>이 빈은 {@code op.auth.dev-stub=false}일 때만 등록된다</b>({@code JwtAuthConfig}).
 * dev 스텁으로 도는 팀원 로컬은 이 검증을 만나지 않으므로 시크릿 없이도 기동한다.
 *
 * @param secret      HMAC 비밀키(≥32바이트). 운영/개발 값이 달라야 한다
 * @param accessTtl   access 토큰 수명. 짧게 두고 refresh 회전으로 연장한다
 * @param refreshTtl  refresh 토큰 수명 = {@code auth_sessions.expires_at} 계산 기준
 * @param cookieSecure  Secure 속성. localhost(http)는 false, 배포(https)는 true — W5 배포 시 전환
 * @param issuer      토큰 발급자 식별자. 다른 환경 토큰이 섞이는 것을 막는다
 */
@ConfigurationProperties(prefix = "op.auth.jwt")
public record JwtProperties(
        String secret,
        Duration accessTtl,
        Duration refreshTtl,
        boolean cookieSecure,
        String issuer
) {

    /** HS256 최소 키 길이(바이트). RFC 7518 §3.2 — 해시 출력 길이 이상. */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "op.auth.jwt.secret 이 없거나 " + MIN_SECRET_BYTES + "바이트 미만입니다. "
                    + ".env 의 JWT_SECRET 을 설정하십시오.");
        }
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(30);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(14);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "openplan";
        }
    }
}
