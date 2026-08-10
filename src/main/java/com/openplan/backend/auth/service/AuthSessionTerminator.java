package com.openplan.backend.auth.service;

import com.openplan.backend.auth.domain.AuthSessionStatus;
import com.openplan.backend.auth.repository.AuthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 세션 종료를 <b>호출자의 롤백과 무관하게</b> 확정하는 자리.
 *
 * <p><b>왜 별도 빈인가.</b> 토큰 갱신 실패 경로는 "세션을 끊고 → 401을 던진다" 순서인데,
 * 그 401({@code OpenPlanException})은 {@code RuntimeException}이라 Spring이 트랜잭션을 롤백한다.
 * 같은 트랜잭션에서 폐기하면 <b>폐기까지 함께 되돌아가</b> 탈취된 토큰으로 열린 세션이 그대로 살아남는다.
 * 실제로 그렇게 구현했다가 재사용 탐지 테스트가 "활성 세션 1개"로 실패해 드러난 결함이다.
 *
 * <p>그래서 {@link Propagation#REQUIRES_NEW}로 <b>독립 트랜잭션</b>을 열어 먼저 커밋시킨다.
 * 자기 호출(self-invocation)은 프록시를 타지 않아 전파 설정이 무시되므로, 같은 클래스의 private
 * 메서드가 아니라 별도 빈이어야 한다.
 *
 * <p>대상 행이 호출자 트랜잭션과 겹치지 않는다는 점이 전제다 — 실패 경로에서는 읽기만 했고,
 * 여기서 바꾸는 것은 아직 손대지 않은 다른 행들이다.
 */
@Service
public class AuthSessionTerminator {

    private final AuthSessionRepository authSessionRepository;

    public AuthSessionTerminator(AuthSessionRepository authSessionRepository) {
        this.authSessionRepository = authSessionRepository;
    }

    /**
     * 재사용 탐지 — 해당 사용자의 활성 세션 전부 폐기(전 기기 로그아웃).
     *
     * @return 폐기된 세션 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllActive(UUID userId) {
        return authSessionRepository.revokeAllByUserIdAndStatus(userId, AuthSessionStatus.ACTIVE);
    }

    /** 시간 만료 확정 — 재사용이 아니므로 이 세션 하나만 닫는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(UUID sessionId) {
        authSessionRepository.expireById(sessionId);
    }
}
