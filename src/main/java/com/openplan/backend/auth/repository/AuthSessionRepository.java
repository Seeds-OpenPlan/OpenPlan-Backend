package com.openplan.backend.auth.repository;

import com.openplan.backend.auth.domain.AuthSession;
import com.openplan.backend.auth.domain.AuthSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * refresh 세션 리포지토리(ST-B1-02).
 *
 * <p>조회는 <b>해시로만</b> 한다 — 원문은 서버에 없고, {@code refresh_token_hash}가 UNIQUE라
 * 단건 조회가 성립한다.
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    /**
     * 회전·재사용 판정의 입구. <b>상태로 거르지 않고 해시로만 찾는다</b> —
     * EXPIRED/REVOKED 행도 찾아야 "이미 쓴 토큰이 또 왔다"를 알 수 있기 때문이다.
     * 상태 판정은 조회 이후 서비스가 한다(ST-B1-02 AC3).
     */
    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * 해당 사용자의 살아 있는 세션 일괄 폐기.
     *
     * <p>쓰이는 곳: 재사용 탐지(탈취 의심 — 전 기기 로그아웃) · 비밀번호 변경/재설정(ST-B1-05 AC4) ·
     * 계정 비활성화(ACCT-04).
     *
     * <p>벌크 UPDATE라 영속성 컨텍스트를 건너뛴다 — 같은 트랜잭션에서 이미 로드한 엔티티가 있다면
     * 그 인스턴스는 갱신되지 않으므로, 호출 후 그 엔티티를 다시 쓰지 않도록 순서를 잡는다.
     *
     * @return 폐기된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s
               set s.status = com.openplan.backend.auth.domain.AuthSessionStatus.REVOKED
             where s.userId = :userId
               and s.status = :status
            """)
    int revokeAllByUserIdAndStatus(@Param("userId") UUID userId, @Param("status") AuthSessionStatus status);

    /**
     * 시간 만료된 세션 표시. ACTIVE인 것만 바꾸므로 이미 EXPIRED/REVOKED인 행은 건드리지 않는다
     * (재사용 탐지의 증거를 덮어쓰지 않기 위함).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s
               set s.status = com.openplan.backend.auth.domain.AuthSessionStatus.EXPIRED
             where s.sessionId = :sessionId
               and s.status = com.openplan.backend.auth.domain.AuthSessionStatus.ACTIVE
            """)
    int expireById(@Param("sessionId") UUID sessionId);
}
