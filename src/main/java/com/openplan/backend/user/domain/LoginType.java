package com.openplan.backend.user.domain;

/**
 * 로그인 유형 — users.login_type(VARCHAR(10), CHECK ck_users_login_type). 로컬 계정/소셜 계정 구분.
 *
 * <p>이름이 곧 저장 문자열이다(@Enumerated STRING). LOCAL은 password_hash, SOCIAL은
 * social_provider(+id)가 필수라는 DB CHECK(ck_users_credential)의 분기 키.
 */
public enum LoginType {
    LOCAL, SOCIAL
}
