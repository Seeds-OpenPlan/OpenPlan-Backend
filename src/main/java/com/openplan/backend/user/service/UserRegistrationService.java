package com.openplan.backend.user.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.onboarding.domain.OnboardingProgress;
import com.openplan.backend.onboarding.repository.OnboardingProgressRepository;
import com.openplan.backend.user.domain.SocialProvider;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserProfile;
import com.openplan.backend.user.dto.SignUpRequest;
import com.openplan.backend.user.dto.SignUpResponse;
import com.openplan.backend.user.repository.UserProfileRepository;
import com.openplan.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 회원가입 서비스 (ST-B1-04 · AUTH-03).
 *
 * <p>가입 한 번에 <b>세 행</b>이 생긴다 — {@code users} · {@code user_profiles} ·
 * {@code onboarding_progress}. 뒤 둘은 생성 엔드포인트가 계약에 없고 각 서비스가 행 부재를
 * E-COM-004로 처리하므로, 여기서 만들어 두지 않으면 가입 직후 온보딩이 시작되지 않는다.
 * 단일 트랜잭션이라 셋 중 하나라도 실패하면 계정 자체가 남지 않는다.
 *
 * <p><b>쿠키를 발급하지 않는다</b> — 가입은 로그인이 아니다. 이메일 인증(AUTH-04) 전까지
 * 로그인은 403 E-AUTH-005로 막힌다.
 */
@Service
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final OnboardingProgressRepository onboardingProgressRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserClock clock;

    public UserRegistrationService(UserRepository userRepository,
                                   UserProfileRepository userProfileRepository,
                                   OnboardingProgressRepository onboardingProgressRepository,
                                   PasswordEncoder passwordEncoder,
                                   UserClock clock) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.onboardingProgressRepository = onboardingProgressRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * POST /users — 계정 생성 후 이메일 인증 단계로 넘긴다.
     *
     * <p>중복 판정을 <b>두 겹</b>으로 둔다. 사전 조회는 흔한 경우에 친절한 409를 주기 위한 것이고,
     * 동시에 같은 이메일이 들어오는 경합은 조회로 막을 수 없으므로 {@code users.email} UNIQUE 위반을
     * 잡아 같은 409로 변환한다. 이 catch가 없으면 경합이 500(E-COM-005)으로 새어 나간다 —
     * check-then-act 구조가 흔히 남기는 구멍이다.
     */
    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new OpenPlanException(ErrorCode.E_AUTH_003);
        }

        User user = User.createLocal(email, passwordEncoder.encode(request.password()), clock.now());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 경합으로 UNIQUE(email) 위반 — 사전 조회를 통과한 뒤 다른 요청이 먼저 넣은 경우.
            throw new OpenPlanException(ErrorCode.E_AUTH_003);
        }

        createAccountRows(user);

        return new SignUpResponse(user.getUserId(), true);
    }

    /**
     * 소셜 가입 (ST-B1-03 · AUTH-02) — 제공자 인증을 마친 뒤 호출된다.
     *
     * <p>로컬 가입과 <b>딸린 행 생성은 동일</b>하다. 프로필·온보딩 행이 없으면 가입 경로가 무엇이든
     * 온보딩이 시작되지 않으므로, 그 규칙을 {@link #createAccountRows} 한 곳에 모아 두 경로가 갈라지지 않게 한다.
     *
     * <p>중복 판정(같은 이메일의 기존 계정)은 <b>호출부가 이미 끝냈다</b> — 소셜은 "별개 계정을 만들지 않는다"는
     * 판정(ST-B1-03 AC2)이 필요해 여기서 단순 예외로 처리할 수 없기 때문이다.
     */
    @Transactional
    public User registerSocial(String email, SocialProvider provider, String providerUserId) {
        User user = User.createSocial(email, provider, providerUserId, clock.now());
        userRepository.saveAndFlush(user);
        createAccountRows(user);
        return user;
    }

    /**
     * 계정에 딸린 필수 행 — 프로필·온보딩 진행 상태.
     *
     * <p>둘 다 생성 엔드포인트가 계약에 없고 각 서비스가 행 부재를 E-COM-004로 처리한다.
     * 즉 <b>가입이 만들지 않으면 아무도 만들지 않는다.</b>
     */
    private void createAccountRows(User user) {
        userProfileRepository.save(
                UserProfile.createInitial(user.getUserId(), provisionalName(user.getEmail())));
        onboardingProgressRepository.save(OnboardingProgress.createInitial(user.getUserId()));
    }

    /**
     * 이메일 정규화 — trim + 소문자.
     *
     * <p>{@code users.email}은 UNIQUE지만 대소문자를 구분하므로, 정규화하지 않으면
     * {@code A@b.com}과 {@code a@b.com}이 <b>별개 계정</b>이 된다. 가입과 로그인이 같은 규칙을 쓰도록
     * 정규화 지점을 이 한 곳으로 모은다({@code AuthService}가 이 메서드를 공유한다).
     */
    public static String normalizeEmail(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 잠정 표시 이름 — 이메일 아이디부를 {@code user_profiles.name}(NOT NULL, 50자) 한도로 자른다.
     * ONB-02에서 사용자가 덮어쓴다. 근거와 대안은 {@link UserProfile#createInitial} javadoc 참조.
     */
    private static String provisionalName(String email) {
        String localPart = email.substring(0, email.indexOf('@'));
        return localPart.length() <= UserProfile.NAME_MAX
                ? localPart
                : localPart.substring(0, UserProfile.NAME_MAX);
    }
}
