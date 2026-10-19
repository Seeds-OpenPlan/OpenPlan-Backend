package com.openplan.backend.push.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 8292 (VAPID) — 요청마다 서명하는 ES256 JWT. {@code Authorization: vapid t=&lt;이 토큰&gt;, k=&lt;공개키&gt;}
 * 헤더로 실려 나간다({@code HttpWebPushSender}).
 *
 * <p>캐시하지 않고 발송마다 새로 서명한다 — ECDSA 서명은 수 ms라 이 서비스의 발송량(분당 발송기 1틱)에서는
 * 캐시의 복잡도(만료 추적·audience별 분리)를 들일 이유가 없다. "exp는 요청 시점부터 24시간 이내"(RFC §2)만
 * 지키면 되므로 호출자가 매번 타당한 ttl을 넘기면 충분하다.
 */
public final class VapidJwtSigner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int P256_FIELD_SIZE = 32;

    private VapidJwtSigner() {
    }

    /**
     * @param audience 푸시 서비스 origin(scheme+host, RFC §2 "aud" — 포트가 기본이 아니면 포함)
     * @param subject  {@code mailto:} 또는 {@code https:} 연락처(RFC §2.1 "sub")
     * @param expiresAt 만료 시각 — 호출자가 "지금부터 24시간 이내"를 보장해야 한다(RFC §2 MUST)
     */
    public static String sign(String audience, String subject, Instant expiresAt, ECPrivateKey privateKey)
            throws GeneralSecurityException {
        try {
            String headerB64 = base64Url(JSON.writeValueAsBytes(header()));
            String bodyB64 = base64Url(JSON.writeValueAsBytes(claims(audience, subject, expiresAt)));
            String signingInput = headerB64 + "." + bodyB64;

            byte[] der = signDer(signingInput.getBytes(StandardCharsets.US_ASCII), privateKey);
            byte[] jose = EcdsaSignatureCodec.derToJose(der, P256_FIELD_SIZE);
            return signingInput + "." + base64Url(jose);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // header/claims는 고정 키의 Map 리터럴이라 직렬화 실패는 설계상 불가능 — 방어적 변환만.
            throw new IllegalStateException("VAPID JWT 클레임 직렬화 실패", e);
        }
    }

    private static Map<String, Object> header() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("typ", "JWT");
        h.put("alg", "ES256");
        return h;
    }

    private static Map<String, Object> claims(String audience, String subject, Instant expiresAt) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("aud", audience);
        c.put("exp", expiresAt.getEpochSecond());
        c.put("sub", subject);
        return c;
    }

    private static byte[] signDer(byte[] signingInput, ECPrivateKey privateKey) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(signingInput);
        return signature.sign();
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
