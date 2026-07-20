package com.openplan.backend.user.domain;

/**
 * 소셜 제공자 — users.social_provider(VARCHAR(10), nullable, CHECK ck_users_social_provider).
 *
 * <p>NAVER는 확정 스택 R4에 의해 필수(구글·카카오와 동급). 로컬 계정은 이 값이 null.
 */
public enum SocialProvider {
    GOOGLE, NAVER, KAKAO
}
