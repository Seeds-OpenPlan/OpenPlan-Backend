package com.openplan.backend.user.service;

import com.openplan.backend.auth.domain.AuthSessionStatus;
import com.openplan.backend.auth.repository.AuthSessionRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.dto.ChangePasswordRequest;
import com.openplan.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 비밀번호 변경 단위 테스트(DB 불요 — 리포지토리 목킹).
 *
 * <p>{@link PasswordEncoder}는 목이 아니라 실제 {@link BCryptPasswordEncoder}를 쓴다. 목을 쓰면
 * "matches 가 true 를 돌려주도록 시켰다"만 확인하게 되어, 정작 검증하려는 <b>해시 대조가 실제로
 * 성립하는지</b>를 못 본다. 강도는 테스트 속도를 위해 최소값(4)으로 낮춘다.
 */
@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CURRENT = "current-pw-1234";

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthSessionRepository authSessionRepository;

    @org.mockito.Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @InjectMocks
    private PasswordChangeService service;

    @Test
    void changePassword_현재_비밀번호가_맞으면_교체하고_세션을_끊는다() {
        User user = localUser(passwordEncoder.encode(CURRENT));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(authSessionRepository.revokeAllByUserIdAndStatus(USER_ID, AuthSessionStatus.ACTIVE)).thenReturn(2);

        service.changePassword(USER_ID, new ChangePasswordRequest(CURRENT, "brand-new-pw-1234"));

        // 저장된 해시가 새 비밀번호와 대조된다 — 원문이 그대로 들어가지 않았음도 함께 본다
        assertThat(user.getPasswordHash()).isNotEqualTo("brand-new-pw-1234");
        assertThat(passwordEncoder.matches("brand-new-pw-1234", user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT, user.getPasswordHash())).isFalse();
        verify(authSessionRepository).revokeAllByUserIdAndStatus(USER_ID, AuthSessionStatus.ACTIVE);
    }

    @Test
    void changePassword_현재_비밀번호가_틀리면_E_USER_001_이고_아무것도_바뀌지_않는다() {
        String original = passwordEncoder.encode(CURRENT);
        User user = localUser(original);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(USER_ID,
                new ChangePasswordRequest("wrong-password", "brand-new-pw-1234")))
                .isInstanceOfSatisfying(OpenPlanException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_USER_001));

        assertThat(user.getPasswordHash()).isEqualTo(original);
        verify(authSessionRepository, never()).revokeAllByUserIdAndStatus(eq(USER_ID), eq(AuthSessionStatus.ACTIVE));
    }

    @Test
    void changePassword_소셜_계정은_바꿀_비밀번호가_없어_E_USER_001() {
        User user = localUser(null); // 소셜 가입 — passwordHash 가 null
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(USER_ID,
                new ChangePasswordRequest(CURRENT, "brand-new-pw-1234")))
                .isInstanceOfSatisfying(OpenPlanException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_USER_001));

        assertThat(user.getPasswordHash()).isNull();
        verify(authSessionRepository, never()).revokeAllByUserIdAndStatus(eq(USER_ID), eq(AuthSessionStatus.ACTIVE));
    }

    @Test
    void changePassword_없는_사용자는_E_COM_004() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(USER_ID,
                new ChangePasswordRequest(CURRENT, "brand-new-pw-1234")))
                .isInstanceOfSatisfying(OpenPlanException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }

    private static User localUser(String passwordHash) {
        User u;
        try {
            java.lang.reflect.Constructor<User> ctor = User.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            u = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 픽스처 생성 실패: User", e);
        }
        ReflectionTestUtils.setField(u, "userId", USER_ID);
        ReflectionTestUtils.setField(u, "email", "a@ex.com");
        ReflectionTestUtils.setField(u, "passwordHash", passwordHash);
        return u;
    }
}
