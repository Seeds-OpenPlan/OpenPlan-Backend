package com.openplan.backend.auth.domain;

/**
 * 링크 토큰 종류 — {@code auth_tokens.token_type}({@code ck_auth_tokens_type} CHECK와 1:1).
 *
 * <p>{@link #EXTERNAL_AUTH}는 외부 캘린더 연동(ST-B1-11) 몫이라 ②에서는 발급하지 않는다.
 * 열거에 남겨 두는 것은 CHECK 제약과 1:1을 유지하기 위해서다.
 */
public enum AuthTokenType {
    /** 가입 후 이메일 소유 확인 (AUTH-04). */
    EMAIL_VERIFICATION,
    /** 비밀번호 재설정 링크 (AUTH-05/06). */
    PASSWORD_RESET,
    /** 외부 연동 토큰 — ST-B1-11 소관. */
    EXTERNAL_AUTH
}
