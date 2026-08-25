package com.openplan.backend.user.service;

import com.openplan.backend.auth.service.AuthSessionTerminator;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.dto.DeactivationResponse;
import com.openplan.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 계정 비활성화 (ACCT-04·06 / {@code DELETE /users/me}).
 *
 * <p><b>지우지 않고 잠근다.</b> 30일 복구창 안에는 {@code POST /auth/reactivations} 로 되살릴 수 있고,
 * 그 뒤에 배치가 실제로 삭제한다(NFR-007). 이 순서가 뒤집히면 사용자가 실수로 누른 것을 되돌릴 수 없다.
 *
 * <p><b>전 세션을 끊는다.</b> 상태만 바꾸고 세션을 두면, 이미 로그인한 브라우저는 계속 쓸 수 있다 —
 * 비활성화가 다음 로그인 시점에야 효력이 생기는 셈이라 사용자가 "껐는데 켜져 있다" 를 본다.
 */
@Service
public class AccountDeactivationService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeactivationService.class);

    /**
     * 🔴 복구창 30일. 계약(NFR-007)이 못박은 값이라 설정으로 빼지 않았다 — 환경마다 다르면
     * "언제까지 되돌릴 수 있나" 를 사용자에게 한 문장으로 말할 수 없다.
     */
    private static final Duration RECOVERY_WINDOW = Duration.ofDays(30);

    private final UserRepository userRepository;
    private final AuthSessionTerminator terminator;
    private final UserClock clock;

    public AccountDeactivationService(UserRepository userRepository,
                                      AuthSessionTerminator terminator, UserClock clock) {
        this.userRepository = userRepository;
        this.terminator = terminator;
        this.clock = clock;
    }

    @Transactional
    public DeactivationResponse deactivate(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        Instant now = clock.now();
        user.deactivate(now, RECOVERY_WINDOW);

        // 세션 종료는 상태 변경 뒤에 한다 — 순서가 반대면 그 사이에 들어온 요청이 아직 ACTIVE 를 본다.
        int revoked = terminator.revokeAllActive(userId);
        log.info("계정 비활성화 — 세션 {}건 종료, 복구 기한 {}", revoked, user.getScheduledDeletionAt());

        return new DeactivationResponse(user.getScheduledDeletionAt());
    }
}
