package com.openplan.backend.user.dto;

import com.openplan.backend.user.domain.LoginType;
import com.openplan.backend.user.domain.SocialProvider;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserProfile;
import com.openplan.backend.user.domain.Weekday;

import java.util.UUID;

/**
 * 내 계정+프로필 응답 — openapi {@code UserProfile} 스키마와 1:1.
 *
 * <p>enum 필드는 Jackson 기본 직렬화로 이름 문자열이 된다(LOCAL·GOOGLE·MON …) — 스키마의 enum 값과 일치.
 * {@code socialProvider}는 로컬 계정에서 null이며, 키는 유지된다(FE가 존재를 전제로 분기).
 */
public record UserProfileResponse(
        UUID userId,
        String email,
        LoginType loginType,
        SocialProvider socialProvider,
        String name,
        String purpose,
        String timezone,
        Weekday weekStartDay
) {

    public static UserProfileResponse from(User user, UserProfile profile) {
        return new UserProfileResponse(
                user.getUserId(),
                user.getEmail(),
                user.getLoginType(),
                user.getSocialProvider(),
                profile.getName(),
                profile.getPurpose(),
                profile.getTimezone(),
                profile.getWeekStartDay());
    }
}
