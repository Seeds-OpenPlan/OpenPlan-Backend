package com.openplan.backend.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 링크에 실리는 불투명 토큰의 생성·해시 (ST-B1-04/05).
 *
 * <p><b>원문은 링크에만, 서버에는 해시만.</b> {@code auth_tokens.token_hash}가 UNIQUE라 해시 한 건으로
 * 토큰을 찾을 수 있고, DB가 유출돼도 유효한 인증·재설정 링크를 복원할 수 없다.
 *
 * <p><b>BCrypt를 쓰지 않는 이유</b>는 refresh 토큰과 같다 — salt가 붙으면 "값으로 찾기"(UNIQUE 인덱스 탐색)가
 * 성립하지 않는다. 고엔트로피 난수라 사전공격 대상이 아니므로 느린 해시가 필요 없다.
 *
 * <p><b>{@code JwtService}에 같은 기능이 있는데 왜 따로 두는가.</b> 그쪽은
 * {@code op.auth.dev-stub=false}일 때만 등록된다(D-32 — 팀원 로컬이 시크릿 없이 뜨도록).
 * 이메일 인증은 <b>dev 스텁 환경에서도 동작해야</b> 하므로(가입 흐름을 로컬에서 끝까지 밟을 수 있어야 한다)
 * 그 조건부 빈에 묶을 수 없다. 정적 유틸이라 주입도 필요 없다.
 */
public final class OpaqueTokens {

    /** 원문 바이트 수. 128비트로 충분하나 여유를 둔다(refresh 토큰과 동일 기준). */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {
    }

    /** URL에 그대로 실을 수 있는 형태(base64url, 패딩 없음). */
    public static String generate() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** {@code auth_tokens.token_hash}에 저장할 값. */
    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
