package com.openplan.backend.push.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Optional;

/**
 * VAPID 서버 키 ({@code op.push.vapid.*}). 소셜 로그인 자격증명({@code OAuthProperties})과 같은 패턴 —
 * 셋 중 하나라도 비면 <b>기동은 하되 발송기는 아무것도 하지 않는다</b>(ADR-0015 결정 ⑤).
 *
 * @param publicKey  base64url, 비압축 P-256 점(65바이트) — 브라우저 {@code applicationServerKey}에 그대로 쓰인다
 * @param privateKey base64url, 32바이트 스칼라 — ECDSA 서명(JWT)에만 쓰고 메시지 암호화에는 쓰지 않는다
 *                   (암호화용 ECDH 키는 발송마다 새로 만드는 1회용 키라 VAPID 키와 무관하다 — RFC 8291 §3.1)
 * @param subject    JWT {@code sub} 클레임 — {@code mailto:} 또는 {@code https:} (RFC 8292 §2.1)
 */
@ConfigurationProperties(prefix = "op.push.vapid")
public record VapidProperties(String publicKey, String privateKey, String subject) {

    /** 셋 다 있어야 "설정됨"이다 — 하나라도 비면 발송기·공개키 조회 양쪽이 off로 취급한다. */
    public boolean isConfigured() {
        return notBlank(publicKey) && notBlank(privateKey) && notBlank(subject);
    }

    public Optional<VapidProperties> configuredOrEmpty() {
        return isConfigured() ? Optional.of(this) : Optional.empty();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
