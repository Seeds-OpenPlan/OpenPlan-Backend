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
 * refresh 세션 엔티티 — {@code auth_sessions}(V1 baseline). 스키마 델타 0.
 *
 * <p><b>이 행이 존재하는 이유는 refresh를 폐기할 수 있게 하기 위해서다.</b> access(JWT)는 무상태라
 * 발급 후 취소가 불가능하고, 그 대가로 수명이 짧다(30분). 대신 refresh는 여기 행으로 남겨
 * 회전·폐기·재사용 탐지가 가능하다 — AUTH-08이 요구하는 것이 정확히 그것이다(ADR-0001).
 *
 * <p><b>원문은 저장하지 않는다.</b> {@code refreshTokenHash}는 SHA-256 결과이며
 * ({@link com.openplan.backend.global.security.JwtService#hashRefreshToken}), 컬럼이 UNIQUE인 덕에
 * 해시 한 건으로 세션을 직접 찾을 수 있다. BCrypt를 쓰지 않는 이유도 이것이다 — salt가 붙으면
 * 인덱스 조회가 성립하지 않는다.
 *
 * <p><b>회전은 행 갱신이 아니라 상태 전이 + 신규 행</b>이다. 소진된 행을 {@link AuthSessionStatus#EXPIRED}로
 * 남겨 두어야 같은 해시가 다시 왔을 때 재사용임을 알 수 있다({@link AuthSessionStatus} javadoc).
 *
 * <p>{@code previousPath}(NFR-004)는 만료 시점의 화면 경로다. 재로그인 후 사용자를 원래 있던 곳으로
 * 되돌리기 위한 재료이며, 서버가 해석하지 않고 그대로 보관·반환한다.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    /** {@code previous_path} 컬럼 길이(VARCHAR(255)). 초과분은 저장 전에 잘라 낸다. */
    public static final int PREVIOUS_PATH_MAX = 255;

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "refresh_token_hash", nullable = false, updatable = false, length = 255)
    private String refreshTokenHash;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** 만료 후 문맥 복귀 경로(AUTH-08 · NFR-004). 없으면 null. */
    @Column(name = "previous_path", length = PREVIOUS_PATH_MAX)
    private String previousPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuthSessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected AuthSession() {
    }

    /**
     * 세션 개시 — 로그인 성공 시, 그리고 회전으로 새 refresh를 낼 때마다 한 행씩 생긴다.
     * id·시각은 애플리케이션이 부여한다(TaskCategory와 동일 관례 — flush 전 응답 조립 가능, 시간 소스는 UserClock).
     *
     * @param refreshTokenHash 원문이 아니라 해시. 원문은 쿠키로만 나가고 서버에 남기지 않는다
     * @param previousPath     복귀 경로. null 허용이며 길이 초과분은 여기서 잘린다
     */
    public static AuthSession start(UUID userId, String refreshTokenHash, Instant expiresAt,
                                    String previousPath, Instant now) {
        AuthSession s = new AuthSession();
        s.sessionId = UUID.randomUUID();
        s.userId = userId;
        s.refreshTokenHash = refreshTokenHash;
        s.startedAt = now;
        s.expiresAt = expiresAt;
        s.previousPath = truncatePath(previousPath);
        s.status = AuthSessionStatus.ACTIVE;
        s.createdAt = now;
        return s;
    }

    /**
     * 회전으로 소진. <b>삭제하지 않고 EXPIRED로 남긴다</b> — 이 행이 재사용 탐지의 증거다.
     */
    public void expire() {
        this.status = AuthSessionStatus.EXPIRED;
    }

    /**
     * 폐기 — 로그아웃(ACCT-03) · 재사용 탐지 시 일괄 폐기(ST-B1-02 AC3) · 비밀번호 변경(ST-B1-05 AC4).
     */
    public void revoke() {
        this.status = AuthSessionStatus.REVOKED;
    }

    /**
     * 회전 가능 여부. 상태가 ACTIVE이고 아직 만료 시각 전이어야 한다.
     *
     * <p>false를 돌려주는 경우가 둘인데 의미가 다르다 — 상태가 ACTIVE가 아니면 <b>재사용 의심</b>이고,
     * 시각만 지났으면 <b>정상 만료</b>다. 그 구분은 호출부(갱신 서비스)가 한다.
     */
    public boolean isUsableAt(Instant now) {
        return status == AuthSessionStatus.ACTIVE && now.isBefore(expiresAt);
    }

    private static String truncatePath(String path) {
        if (path == null) {
            return null;
        }
        return path.length() <= PREVIOUS_PATH_MAX ? path : path.substring(0, PREVIOUS_PATH_MAX);
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getPreviousPath() {
        return previousPath;
    }

    public AuthSessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
