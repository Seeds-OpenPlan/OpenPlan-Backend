package com.openplan.backend.externalcalendar.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 제공자 OAuth 토큰의 대칭 암호화 (ST-B1-11 · AES-GCM).
 *
 * <p><b>왜 해시가 아니라 암호화인가.</b> {@code auth_tokens.token_hash}(V202608101930)는 단방향이다 —
 * 제출된 값이 맞는지 대조하고 1회 쓰고 버리는 링크용이기 때문이다. 캘린더 토큰은 정반대로
 * <b>제공자 API를 호출하려고 원문을 되읽어야</b> 하므로 복호 가능한 방식이어야 한다.
 *
 * <p><b>GCM인 이유</b>: 인증 태그가 붙어 위조·잘림을 복호 단계에서 잡는다. CBC였다면 DB가 변조돼도
 * 조용히 쓰레기 평문이 나와 제공자 호출이 알 수 없는 이유로 실패한다.
 *
 * <p><b>키가 없어도 기동한다</b>(D-32와 같은 판단) — 캘린더 연동을 쓰지 않는 팀원 로컬까지 함께
 * 멈추면 안 된다. 대신 실제 암·복호 시점에 {@link IllegalStateException}으로 분명하게 실패한다.
 * 임시 키를 자동 생성하지 않는 이유는, 재기동 때마다 키가 바뀌어 <b>이미 저장된 토큰이 조용히
 * 복호 불가</b>가 되기 때문이다 — 그건 실패가 아니라 침묵이다.
 */
@Component
public class ExternalTokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;      // GCM 권장 96비트
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;        // AES-256

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    /**
     * @param base64Key {@code EXT_TOKEN_KEY} — base64로 인코딩한 32바이트. 비어 있으면 키 없이 기동한다.
     */
    public ExternalTokenCipher(@Value("${op.external-calendar.token-key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    /** 평문 토큰 → base64(nonce || ciphertext || tag). */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 예외 메시지에 평문이 실리지 않도록 원인만 넘긴다.
            throw new IllegalStateException("외부 토큰 암호화 실패", e);
        }
    }

    /** base64(nonce || ciphertext || tag) → 평문 토큰. 태그가 맞지 않으면 복호 단계에서 실패한다. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length <= NONCE_BYTES) {
                throw new IllegalStateException("외부 토큰 암호문이 너무 짧다");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(raw, 0, nonce, 0, NONCE_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("외부 토큰 복호화 실패", e);
        }
    }

    /** 키가 설정돼 있는지 — 연동 시작 전 사전 점검용(설정 누락을 제공자 오류로 오인하지 않도록). */
    public boolean isConfigured() {
        return key != null;
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "EXT_TOKEN_KEY 가 설정되지 않았다 — 외부 캘린더 토큰을 다룰 수 없다. "
                            + "base64로 인코딩한 32바이트를 주입할 것.");
        }
        return key;
    }

    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("EXT_TOKEN_KEY 가 base64 가 아니다", e);
        }
        if (bytes.length != KEY_BYTES) {
            // 여기서만큼은 기동을 막는다 — 잘못된 길이는 "미설정"이 아니라 설정 오류다.
            throw new IllegalStateException(
                    "EXT_TOKEN_KEY 는 32바이트여야 한다 (현재 " + bytes.length + "바이트)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
