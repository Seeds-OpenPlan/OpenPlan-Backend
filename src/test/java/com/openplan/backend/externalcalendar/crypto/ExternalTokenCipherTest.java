package com.openplan.backend.externalcalendar.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 제공자 토큰 암·복호 (ST-B1-11). 순수 단위 — DB·Docker 불요.
 */
class ExternalTokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ExternalTokenCipher cipher = new ExternalTokenCipher(KEY);

    @Test
    @DisplayName("암호화한 토큰을 그대로 복호한다")
    void roundTrip() {
        String token = "ya29.a0AfH6SMBx-provider-access-token";

        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).isNotEqualTo(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문이 된다 — nonce 가 매번 새로 생성된다")
    void nonceVariesPerCall() {
        String token = "same-token";

        assertThat(cipher.encrypt(token)).isNotEqualTo(cipher.encrypt(token));
    }

    @Test
    @DisplayName("암호문이 한 글자라도 변조되면 복호가 실패한다 — GCM 인증 태그")
    void detectsTampering() {
        String encrypted = cipher.encrypt("provider-token");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;                       // 태그 마지막 바이트 뒤집기
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("복호화 실패");
    }

    @Test
    @DisplayName("null 은 그대로 통과한다 — refresh 토큰을 주지 않는 제공자가 있다")
    void passesNullThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("키가 없으면 기동은 되고 사용 시점에 실패한다 — 연동을 안 쓰는 로컬을 막지 않는다")
    void startsWithoutKeyButFailsOnUse() {
        ExternalTokenCipher keyless = new ExternalTokenCipher("");

        assertThat(keyless.isConfigured()).isFalse();
        assertThatThrownBy(() -> keyless.encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXT_TOKEN_KEY");
    }

    @Test
    @DisplayName("키 길이가 32바이트가 아니면 기동 자체를 막는다 — 미설정이 아니라 설정 오류다")
    void rejectsWrongKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new ExternalTokenCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }
}
