package com.openplan.backend.auth.repository;

import com.openplan.backend.auth.domain.AuthToken;
import com.openplan.backend.auth.domain.AuthTokenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 링크 토큰 리포지토리 (ST-B1-04/05).
 *
 * <p>조회는 <b>해시로만</b> 한다 — 원문은 서버에 없고 {@code token_hash}가 UNIQUE라 단건 조회가 성립한다.
 */
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    /**
     * 링크 검증의 입구. <b>상태로 거르지 않는다</b> — 이미 쓴 링크와 없는 링크를 구분해
     * 각각 410/410으로 답하되 로그에서는 갈라 볼 수 있어야 한다(판정은 서비스가 한다).
     */
    Optional<AuthToken> findByTokenHash(String tokenHash);

    /**
     * 재발송 쿨다운 판정용 — 해당 사용자·종류의 <b>가장 최근 발급분</b>.
     * ui-spec §AUTH의 60초 카운트다운이 이 값을 근거로 한다.
     */
    Optional<AuthToken> findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(UUID userId, AuthTokenType tokenType);
}
