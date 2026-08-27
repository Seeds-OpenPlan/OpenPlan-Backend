package com.openplan.backend.auth.service;

import com.openplan.backend.auth.dto.ReactivationRequest;
import com.openplan.backend.auth.repository.AuthSessionRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.onboarding.repository.OnboardingProgressRepository;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserStatus;
import com.openplan.backend.user.repository.UserProfileRepository;
import com.openplan.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 계정 재활성화 상태 판정 단위 테스트(ACCT-05 · DB 불요 — 협력자 전량 목킹).
 *
 * <p>여기서 고정하는 것은 <b>세션 발급이 아니라 상태 기계</b>다. 발급은 로그인과 같은
 * {@code establishSession} 을 그대로 타므로 이미 다른 테스트가 덮는다. 대신 이 경로에만 있는
 * 판정 — 자격 먼저 · 복구창 경과 · 잠금 · 미인증 · 멱등 — 을 분기별로 세운다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceReactivateTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final String EMAIL = "a@ex.com";
    private static final String PASSWORD = "correct-horse-1234";
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private OnboardingProgressRepository onboardingProgressRepository;
    @Mock private AuthSessionRepository authSessionRepository;
    @Mock private AuthSessionTerminator terminator;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ObjectProvider<JwtService> jwtServiceProvider;
    @Mock private ObjectProvider<AuthCookies> authCookiesProvider;
    @Mock private UserClock clock;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, userProfileRepository, onboardingProgressRepository,
                authSessionRepository, terminator, passwordEncoder,
                jwtServiceProvider, authCookiesProvider, clock);
        when(clock.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("복구창 안이면 상태를 되살리고 복구창 흔적을 지운다")
    void reactivatesWithinWindow() {
        User user = deactivated(NOW.minus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(27)));
        givenCredentialsMatch(user);
        // 세션 발급 빈이 없으면 establishSession 이 E-AUTH-011 로 끊긴다 — 상태 전이는 그 전에 끝난다.
        assertThatThrownBy(() -> service.reactivate(new ReactivationRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_AUTH_011));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getScheduledDeletionAt()).isNull();
        assertThat(user.getDeactivationRequestedAt()).isNull();
    }

    @Test
    @DisplayName("복구창이 지났으면 410 E-AUTH-009 이고 상태는 그대로다")
    void rejectsAfterWindow() {
        Instant deletionAt = NOW.minus(Duration.ofSeconds(1));
        User user = deactivated(NOW.minus(Duration.ofDays(31)), deletionAt);
        givenCredentialsMatch(user);

        assertThatThrownBy(() -> service.reactivate(new ReactivationRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_AUTH_009));

        assertThat(user.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(user.getScheduledDeletionAt()).isEqualTo(deletionAt);
    }

    @Test
    @DisplayName("자격이 틀리면 상태를 보기 전에 401 E-AUTH-001 — 비활성 사실이 새지 않는다")
    void rejectsWrongCredentialsBeforeStatus() {
        User user = deactivated(NOW.minus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(27)));
        ReflectionTestUtils.setField(user, "passwordHash", "$2a$04$storedhash");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$04$storedhash")).thenReturn(false);

        assertThatThrownBy(() -> service.reactivate(new ReactivationRequest(EMAIL, "wrong")))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_AUTH_001));

        assertThat(user.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    @DisplayName("잠긴 계정은 되살릴 수 없다 — 401 E-AUTH-002")
    void rejectsLocked() {
        User user = baseUser();
        ReflectionTestUtils.setField(user, "status", UserStatus.LOCKED);
        givenCredentialsMatch(user);

        assertThatThrownBy(() -> service.reactivate(new ReactivationRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_AUTH_002));
    }

    @Test
    @DisplayName("이메일 미인증이면 403 E-AUTH-005 — 되살아난 뒤에도 로그인 관문은 같다")
    void rejectsUnverifiedEmail() {
        User user = deactivated(NOW.minus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(27)));
        ReflectionTestUtils.setField(user, "emailVerified", false);
        givenCredentialsMatch(user);

        assertThatThrownBy(() -> service.reactivate(new ReactivationRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_AUTH_005));

        // 상태는 이미 되살아났다 — 남은 관문은 인증뿐이고, 되돌리면 재요청이 복구창을 다시 소모한다.
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * 위 {@code rejectsUnverifiedEmail} 이 보는 것은 <b>메모리상의 엔티티</b>다. 협력자를 전량 목킹해
     * 트랜잭션이 없으므로, 실제 서비스에서 되살린 것이 롤백돼도 저 단언은 그대로 통과한다 —
     * 실제로 그런 결함이 있었다(AI 리뷰, 2026-08-25).
     *
     * <p>제대로 세우려면 DB 를 띄운 통합 테스트라야 하는데, <b>Docker 가 죽어 있고 이 저장소에는
     * 테스트 CI 가 없다</b>(워크플로는 {@code ai-review.yml} 뿐). 그래서 지금 세울 수 있는 것은
     * "롤백 규칙이 그 자리에 있는가" 까지다 — 행위 증명은 아니지만, <b>누가 이 애노테이션을 지우면
     * 반드시 빨개진다.</b> 결함이 되살아나는 유일한 경로가 그것이다.
     *
     * <p>🔴 DB 가 서면 이 테스트를 <b>실제 왕복으로 교체할 것</b> — 되살린 뒤 403 을 받고,
     * 새 트랜잭션에서 다시 읽어 {@code ACTIVE} · {@code scheduledDeletionAt == null} 을 확인한다.
     */
    @Test
    @DisplayName("재활성화는 예외가 나도 되살린 것을 남긴다 — noRollbackFor 가 지워지면 실패한다")
    void reactivateDoesNotRollBackTheRescue() throws NoSuchMethodException {
        Transactional tx = AuthService.class
                .getMethod("reactivate", ReactivationRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(tx)
                .as("reactivate 는 트랜잭션 경계를 가져야 한다")
                .isNotNull();
        assertThat(tx.noRollbackFor())
                .as("미인증 403 이 되살리기를 함께 지우면, 메일을 인증하는 동안 삭제 배치가 계정을 가져간다")
                .contains(OpenPlanException.class);
    }

    @Test
    @DisplayName("이미 활성인 계정은 오류가 아니라 그대로 로그인 경로로 간다(멱등)")
    void activeAccountIsIdempotent() {
        User user = baseUser(); // ACTIVE
        givenCredentialsMatch(user);

        // 상태 판정을 통과했으므로 세션 발급 단계까지 도달한다(빈이 없어 E-AUTH-011).
        assertThatThrownBy(() -> service.reactivate(new ReactivationRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_AUTH_011));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    private void givenCredentialsMatch(User user) {
        ReflectionTestUtils.setField(user, "passwordHash", "$2a$04$storedhash");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "$2a$04$storedhash")).thenReturn(true);
        when(jwtServiceProvider.getIfAvailable()).thenReturn(null); // 발급 빈 없음 → E-AUTH-011
    }

    private static User baseUser() {
        User u;
        try {
            java.lang.reflect.Constructor<User> ctor = User.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            u = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 픽스처 생성 실패: User", e);
        }
        ReflectionTestUtils.setField(u, "userId", USER_ID);
        ReflectionTestUtils.setField(u, "email", EMAIL);
        ReflectionTestUtils.setField(u, "status", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(u, "emailVerified", true);
        return u;
    }

    private static User deactivated(Instant requestedAt, Instant deletionAt) {
        User u = baseUser();
        ReflectionTestUtils.setField(u, "status", UserStatus.DEACTIVATED);
        ReflectionTestUtils.setField(u, "deactivationRequestedAt", requestedAt);
        ReflectionTestUtils.setField(u, "scheduledDeletionAt", deletionAt);
        return u;
    }
}
