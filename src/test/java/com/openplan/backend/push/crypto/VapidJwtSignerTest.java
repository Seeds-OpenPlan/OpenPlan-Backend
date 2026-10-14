package com.openplan.backend.push.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 8292 (VAPID) JWT — 서명(ES256/JOSE)·헤더 모양을 검증한다. {@link EcdsaSignatureCodec}가
 * DER↔JOSE를 올바르게 오간다는 증거로 두 방향을 각각 쓴다: 우리가 서명한 토큰은 JOSE→DER로 되돌려
 * JDK {@link Signature#verify}로 검증하고(자체 왕복), RFC §2.4의 실제 예시 토큰은 같은 변환으로
 * 외부에서 만들어진 서명이 우리 코드로도 유효하다는 것을 보여준다(독립적인 교차 검증).
 */
class VapidJwtSignerTest {

    private static final int FIELD_SIZE = 32;

    @Test
    @DisplayName("자체 서명 → JOSE를 DER로 되돌려 JDK Signature.verify로 검증 성공")
    void signedTokenVerifiesWithOwnKey() throws Exception {
        KeyPair keyPair = generateP256KeyPair();
        Instant expiresAt = Instant.parse("2026-10-09T00:00:00Z");

        String jwt = VapidJwtSigner.sign("https://push.example.net", "mailto:ops@openplan.example",
                expiresAt, (ECPrivateKey) keyPair.getPrivate());

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        String header = decodeToString(parts[0]);
        assertThat(header).contains("\"typ\":\"JWT\"").contains("\"alg\":\"ES256\"");
        String body = decodeToString(parts[1]);
        assertThat(body).contains("\"aud\":\"https://push.example.net\"")
                .contains("\"exp\":" + expiresAt.getEpochSecond())
                .contains("\"sub\":\"mailto:ops@openplan.example\"");

        assertThat(verify(parts[0] + "." + parts[1], parts[2], (ECPublicKey) keyPair.getPublic())).isTrue();
    }

    @Test
    @DisplayName("다른 키로는 검증이 실패한다 — 서명이 실제로 그 개인키에 묶여 있다")
    void signatureDoesNotVerifyWithWrongKey() throws Exception {
        KeyPair signingKey = generateP256KeyPair();
        KeyPair otherKey = generateP256KeyPair();

        String jwt = VapidJwtSigner.sign("https://push.example.net", "mailto:ops@openplan.example",
                Instant.now().plusSeconds(3600), (ECPrivateKey) signingKey.getPrivate());
        String[] parts = jwt.split("\\.");

        assertThat(verify(parts[0] + "." + parts[1], parts[2], (ECPublicKey) otherKey.getPublic())).isFalse();
    }

    /**
     * RFC 8292 §2.4 예시 토큰(실제 명세 텍스트에서 그대로 가져온 값, 줄바꿈만 제거) — 우리 구현이
     * 아니라 <b>RFC 문서가 만든 서명</b>을 우리 {@link EcdsaSignatureCodec#joseToDer}로 변환해 검증한다.
     */
    @Test
    @DisplayName("RFC 8292 §2.4 예시 토큰이 예시 공개키로 검증된다")
    void rfc8292ExampleTokenVerifies() throws Exception {
        String jwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9"
                + ".eyJhdWQiOiJodHRwczovL3B1c2guZXhhbXBsZS5uZXQiLCJleHAiOjE0NTM1MjM3NjgsInN1YiI6Im1haWx0bzpwdXNoQGV4YW1wbGUuY29tIn0"
                + ".i3CYb7t4xfxCDquptFOepC9GAu_HLGkMlMuCGSK2rpiUfnK9ojFwDXb1JrErtmysazNjjvW2L9OkSSHzvoD1oA";
        // RFC §2.4 "k" 파라미터 — 이 JWT를 서명한 애플리케이션 서버의 공개키(비압축 65바이트).
        String publicKeyBase64Url = "BA1Hxzyi1RUM1b5wjxsn7nGxAszw2u61m164i3MrAIxHF6YK5h4SDYic-dR"
                + "uU_RCPCfA5aq9ojSwk5Y2EmClBPs";

        String[] parts = jwt.split("\\.");
        ECPublicKey publicKey = EcKeys.decodePublicKeyBase64Url(publicKeyBase64Url);

        assertThat(verify(parts[0] + "." + parts[1], parts[2], publicKey)).isTrue();
    }

    private boolean verify(String signingInput, String signatureBase64Url, ECPublicKey publicKey)
            throws Exception {
        byte[] jose = Base64.getUrlDecoder().decode(signatureBase64Url);
        byte[] der = EcdsaSignatureCodec.joseToDer(jose, FIELD_SIZE);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signature.verify(der);
    }

    private String decodeToString(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    private KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }
}
