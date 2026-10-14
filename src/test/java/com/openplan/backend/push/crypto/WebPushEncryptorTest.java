package com.openplan.backend.push.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 8291 §5 "Push Message Encryption Example" 전체 벡터 재현 — ADR-0015 결정 ⑤가 요구하는 유일한
 * 증명 지점이다. 라이브러리를 쓰지 않고 직접 구현한 {@link WebPushEncryptor}가 맞는지는 이 테스트
 * 하나로 판정한다: salt·발신/수신 키·평문을 RFC 그대로 넣었을 때 <b>바이트 단위로 똑같은 출력</b>이
 * 나와야 한다(헤더 86바이트 + ciphertext — §5 예시 요청의 {@code Content-Length: 145}와 일치).
 */
class WebPushEncryptorTest {

    // RFC 8291 §5 — 비압축 X9.62 점(65바이트)·32바이트 스칼라, 모두 base64url(패딩 없음).
    private static final String RECEIVER_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String RECEIVER_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SENDER_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String SENDER_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final int RECORD_SIZE = 4096; // §5 예시가 쓴 rs

    /** §5 본문 전체(헤더+ciphertext) — 줄바꿈만 제거한 그대로. 이 문자열과 바이트 단위로 같아야 한다. */
    private static final String EXPECTED_BODY_BASE64URL =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
          + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
          + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    @DisplayName("RFC 8291 §5 예시 입력 → 예시 출력과 바이트 단위로 완전히 일치")
    void matchesRfcExampleExactly() throws Exception {
        ECPublicKey receiverPublicKey = EcKeys.decodePublicKeyBase64Url(RECEIVER_PUBLIC);
        ECPublicKey senderPublicKey = EcKeys.decodePublicKeyBase64Url(SENDER_PUBLIC);
        ECPrivateKey senderPrivateKey = EcKeys.decodePrivateKeyBase64Url(SENDER_PRIVATE);
        byte[] authSecret = Base64.getUrlDecoder().decode(AUTH_SECRET);
        byte[] salt = Base64.getUrlDecoder().decode(SALT);
        KeyPair senderKeyPair = new KeyPair(senderPublicKey, senderPrivateKey);

        byte[] actual = WebPushEncryptor.encrypt(receiverPublicKey, authSecret, salt, senderKeyPair,
                RECORD_SIZE, PLAINTEXT.getBytes(StandardCharsets.US_ASCII));

        byte[] expected = Base64.getUrlDecoder().decode(EXPECTED_BODY_BASE64URL);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("헤더(86바이트) 구조 — salt(16)|rs(4)|idlen(1)|keyid(65, 발신자 공개키)")
    void headerLayoutMatchesRfc8188() throws Exception {
        ECPublicKey receiverPublicKey = EcKeys.decodePublicKeyBase64Url(RECEIVER_PUBLIC);
        ECPublicKey senderPublicKey = EcKeys.decodePublicKeyBase64Url(SENDER_PUBLIC);
        ECPrivateKey senderPrivateKey = EcKeys.decodePrivateKeyBase64Url(SENDER_PRIVATE);
        byte[] authSecret = Base64.getUrlDecoder().decode(AUTH_SECRET);
        byte[] salt = Base64.getUrlDecoder().decode(SALT);

        byte[] actual = WebPushEncryptor.encrypt(receiverPublicKey, authSecret, salt,
                new KeyPair(senderPublicKey, senderPrivateKey), RECORD_SIZE,
                PLAINTEXT.getBytes(StandardCharsets.US_ASCII));

        assertThat(actual).hasSizeGreaterThanOrEqualTo(86);
        byte[] saltField = java.util.Arrays.copyOfRange(actual, 0, 16);
        assertThat(saltField).isEqualTo(salt);

        int rs = java.nio.ByteBuffer.wrap(actual, 16, 4).getInt();
        assertThat(rs).isEqualTo(RECORD_SIZE);

        int idlen = actual[20] & 0xFF;
        assertThat(idlen).isEqualTo(65);
        byte[] keyid = java.util.Arrays.copyOfRange(actual, 21, 21 + 65);
        assertThat(keyid).isEqualTo(EcKeys.encodePublicKey(senderPublicKey));

        // 전체 바이트 수 = 헤더(86) + 평문 + 패딩 구분자(1) + AEAD 태그(16). 평문 길이를 리터럴로 다시 세지
        // 않는다 — 위 exactMatch 테스트가 바이트 단위 정답을 이미 증명했으니, 여기서는 그 구조식만 고정한다.
        int plaintextLength = PLAINTEXT.getBytes(StandardCharsets.US_ASCII).length;
        assertThat(actual).hasSize(86 + plaintextLength + 1 + 16);
    }
}
