package com.openplan.backend.user.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserProfile;
import com.openplan.backend.user.domain.Weekday;
import com.openplan.backend.user.dto.UpdateProfileRequest;
import com.openplan.backend.user.dto.UserProfileResponse;
import com.openplan.backend.user.repository.UserProfileRepository;
import com.openplan.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 프로필 도메인 서비스(ST-B1-07) — 내 계정+프로필 조회 및 부분 수정.
 *
 * <p>모든 조회/수정은 인증 주체 UUID로 스코핑된다(@CurrentUser). 프로필은 가입 시 생성되므로
 * 여기서 신규 생성은 하지 않는다 — 없으면 데이터 무결성 위반으로 보고 E-COM-004로 응답한다.
 */
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public UserProfileService(UserRepository userRepository, UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    /** GET /users/me — 계정(email·loginType·socialProvider) + 프로필 조립. */
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(UUID userId) {
        User user = loadUser(userId);
        UserProfile profile = loadProfile(userId);
        return UserProfileResponse.from(user, profile);
    }

    /**
     * PATCH /users/me/profile — 제공된 필드만 수정하고 최신 프로필을 반환한다.
     *
     * <p>더티 체킹으로 UPDATE 된다(명시적 save 불필요). timezone은 실재 IANA 존인지 검증한다 —
     * 형식은 맞아도 존재하지 않는 값(예: "Asia/Nowhere")은 저장을 막고 E-COM-009로 안내.
     */
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = loadUser(userId);
        UserProfile profile = loadProfile(userId);

        // 입력을 먼저 전량 검증·정규화한 뒤에 엔티티를 변형한다(L2) — 검증 실패 시 부분 변형이 남지 않게.
        // 운영에선 @Transactional 롤백이 있지만, 순서 자체를 "검증 후 변형"으로 두어 트랜잭션에 의존하지 않는다.
        Weekday weekStartDay = request.weekStartDay() != null ? parseWeekday(request.weekStartDay()) : null;
        String timezone = request.timezone() != null ? requireValidTimezone(request.timezone()) : null;

        if (request.name() != null) {
            profile.changeName(request.name());
        }
        if (request.purpose() != null) {
            profile.changePurpose(request.purpose());
        }
        if (timezone != null) {
            profile.changeTimezone(timezone);
        }
        if (weekStartDay != null) {
            profile.changeWeekStartDay(weekStartDay);
        }

        return UserProfileResponse.from(user, profile);
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
    }

    private UserProfile loadProfile(UUID userId) {
        return userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
    }

    /** 실재 시간대만 통과. 잘못된 값은 422 E-COM-009(+field). */
    private String requireValidTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
            return timezone;
        } catch (DateTimeException e) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("field", "timezone"));
        }
    }

    /**
     * 검증된 문자열을 Weekday로 변환. 컨트롤러의 @Pattern이 이미 값 집합을 강제하므로 정상 경로에선
     * 예외가 나지 않는다 — 서비스 직접 호출 등 방어 목적으로 잘못된 값은 E-COM-001로 승격한다.
     */
    private Weekday parseWeekday(String value) {
        try {
            return Weekday.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new OpenPlanException(ErrorCode.E_COM_001, Map.of("field", "weekStartDay"));
        }
    }
}
