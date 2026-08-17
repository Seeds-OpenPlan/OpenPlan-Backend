package com.openplan.backend.auth.oauth;

/**
 * 제공자에서 받은 사용자 식별 정보 — 세 제공자의 서로 다른 응답을 하나로 좁힌 형태.
 *
 * @param providerUserId 제공자 측 고유 ID. {@code users.social_provider_user_id}에 들어가며
 *                       {@code ux_users_social_identity} UNIQUE 인덱스가 재로그인 시 계정 중복 생성을 막는다.
 *                       <b>이메일이 아니라 이것이 신원의 기준</b>이다 — 사용자가 제공자 쪽에서 이메일을 바꿔도
 *                       같은 계정으로 남아야 하기 때문이다
 * @param email          이메일. <b>null일 수 있다</b> — 카카오는 선택 동의라 사용자가 거부하면 오지 않는다
 */
public record OAuthUserInfo(String providerUserId, String email) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
