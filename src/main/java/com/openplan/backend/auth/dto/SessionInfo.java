package com.openplan.backend.auth.dto;

import com.openplan.backend.onboarding.domain.OnboardingProgress;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserProfile;
import com.openplan.backend.user.domain.Weekday;

import java.util.UUID;

/**
 * 세션 응답 (AUTH-01/07) — 정본 openapi {@code SessionInfo} 스키마와 1:1.
 * 로그인 성공({@code POST /auth/sessions})과 세션 확인({@code GET /auth/session})이 같은 shape를 쓴다.
 *
 * <p><b>스텁 시절의 {@code authenticated} 필드는 제거했다.</b> 계약에 없는 필드였고, 미인증이면
 * 애초에 401 봉투가 나가므로 200 본문 안에서 "인증됨"을 다시 말할 이유가 없다. 프론트도 이 필드를
 * 읽지 않는다(전수 확인 — 세션 소비처는 {@code useAuth.js}의 {@code data.userId}와 라우터 가드뿐).
 *
 * <p>토큰은 여기 담기지 않는다 — httpOnly 쿠키로만 오간다(ADR-0001 · R8).
 *
 * @param name     표시 이름. 가입 직후 온보딩 전에는 잠정값이며, 계약상 null 허용이다
 * @param timezone IANA 존 이름(프로필 값)
 */
public record SessionInfo(
        UUID userId,
        String name,
        String email,
        boolean onboardingCompleted,
        Weekday weekStartDay,
        String timezone) {

    /**
     * 계정·프로필·온보딩 세 행에서 조립한다. 셋 다 가입 트랜잭션이 함께 만들므로 정상 계정에는 반드시 있다 —
     * 호출부가 부재를 E-COM-004로 처리한다.
     */
    public static SessionInfo of(User user, UserProfile profile, OnboardingProgress progress) {
        return new SessionInfo(
                user.getUserId(),
                profile.getName(),
                user.getEmail(),
                progress.isOnboardingCompleted(),
                profile.getWeekStartDay(),
                profile.getTimezone());
    }
}
