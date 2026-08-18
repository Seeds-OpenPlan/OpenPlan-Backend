package com.openplan.backend.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 링크 토큰 엔티티 — {@code auth_tokens}. 이메일 인증(AUTH-04)과 비밀번호 재설정(AUTH-05/06)이 공유한다.
 *
 * <p><b>원문은 이 행에 없다.</b> {@code tokenHash}는 SHA-256 결과이며
 * ({@link com.openplan.backend.auth.token.OpaqueTokens}), 원문은 메일 링크로만 나간다.
 * 해시 컬럼은 {@code V202608101930} 마이그레이션이 추가했다 — baseline은 ERD를 그대로 따라
 * 토큰 값을 담을 자리를 두지 않았고, 그대로 두면 {@code token_id}를 링크에 실어야 했다.
 *
 * <p><b>1회용 보장(NFR-005)은 {@link #status}와 {@link #usedAt} 두 값이 함께 한다.</b>
 * 사용 시 상태를 {@link AuthTokenStatus#USED}로 바꾸므로 같은 링크는 두 번 통하지 않는다.
 */
@Entity
@Table(name = "auth_tokens")
public class AuthToken {

    @Id
    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 30, updatable = false)
    private AuthTokenType tokenType;

    @Column(name = "token_hash", nullable = false, length = 255, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** 사용 시각(NFR-005 1회용 기록). 미사용이면 null. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuthTokenStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected AuthToken() {
    }

    /**
     * 토큰 발급. id·시각은 애플리케이션이 부여한다(TaskCategory와 동일 관례 — 시간 소스는 UserClock).
     *
     * @param tokenHash 원문이 아니라 해시. 원문은 호출부가 링크에 실어 보내고 버린다
     */
    public static AuthToken issue(UUID userId, AuthTokenType type, String tokenHash,
                                  Instant expiresAt, Instant now) {
        AuthToken t = new AuthToken();
        t.tokenId = UUID.randomUUID();
        t.userId = userId;
        t.tokenType = type;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        t.usedAt = null;
        t.status = AuthTokenStatus.ISSUED;
        t.createdAt = now;
        return t;
    }

    /** 사용 처리 — 같은 링크가 다시 와도 통하지 않게 한다(NFR-005). */
    public void markUsed(Instant now) {
        this.status = AuthTokenStatus.USED;
        this.usedAt = now;
    }

    /** 만료 확정. 시각 비교로도 알 수 있지만, 굳혀 두면 같은 링크의 반복 요청에서 재계산하지 않는다. */
    public void markExpired() {
        this.status = AuthTokenStatus.EXPIRED;
    }

    /** 지금 쓸 수 있는가 — 발급 상태이면서 아직 만료 시각 전. */
    public boolean isUsableAt(Instant now) {
        return status == AuthTokenStatus.ISSUED && now.isBefore(expiresAt);
    }

    public UUID getTokenId() {
        return tokenId;
    }

    public UUID getUserId() {
        return userId;
    }

    public AuthTokenType getTokenType() {
        return tokenType;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public AuthTokenStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
