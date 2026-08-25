package com.openplan.backend.user.service;

import com.openplan.backend.auth.domain.AuthSessionStatus;
import com.openplan.backend.auth.repository.AuthSessionRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.dto.ChangePasswordRequest;
import com.openplan.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 비밀번호 변경 서비스(PATCH /users/me/password · ACCT-02).
 *
 * <p>{@link com.openplan.backend.auth.service.PasswordResetService}(메일 토큰으로 되찾는 경로)와
 * 목적이 다르다. 여기는 <b>비밀번호를 아는 사람이 스스로 바꾸는</b> 경로이고, 본인 확인 수단이
 * 토큰이 아니라 현재 비밀번호다.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordChangeService(UserRepository userRepository,
                                 AuthSessionRepository authSessionRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 현재 비밀번호를 확인한 뒤 교체하고, 열려 있던 세션을 전부 끊는다.
     *
     * <p>세션 폐기는 재설정 경로(PasswordResetService AC4)와 같은 규약이다 — 비밀번호를 바꾸는 이유가
     * "누가 내 계정을 보고 있는 것 같다"인 경우, 세션이 살아 있으면 바꾼 의미가 없다. 성공 경로에서만
     * 일어나므로 같은 트랜잭션에 둔다(실패 시 함께 롤백되는 것이 옳다 — 비밀번호는 그대로인데 세션만
     * 끊기면 안 된다).
     *
     * <p>🔴 소셜 가입 계정은 {@code passwordHash}가 {@code null}이라 바꿀 비밀번호 자체가 없다. 이때도
     * E-USER-001로 답한다 — 계약이 선언한 응답이 200과 400 둘뿐이기도 하고, 별도 코드를 주면 "이 이메일은
     * 소셜 계정"이라는 사실이 인증된 주체 밖으로 새어 나갈 여지를 만든다.
     */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));

        String hash = user.getPasswordHash();
        if (hash == null || !passwordEncoder.matches(request.currentPassword(), hash)) {
            throw new OpenPlanException(ErrorCode.E_USER_001);
        }

        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));

        int revoked = authSessionRepository.revokeAllByUserIdAndStatus(userId, AuthSessionStatus.ACTIVE);
        log.info("비밀번호 변경 완료: userId={} 폐기된 세션={}", userId, revoked);
    }
}
