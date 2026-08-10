package com.openplan.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 계정 루트 엔티티 — {@code users} 테이블(V1 baseline). 스키마 델타 0.
 *
 * <p>ST-B1-07(프로필)이 email·login_type·social_provider만 매핑해 두었고, <b>ST-B1-02/04가 인증에
 * 필요한 나머지를 여기서 채웠다</b>(password_hash·is_email_verified·status·복구창 2개·created_at).
 * 07 시점의 javadoc이 예고한 확장 지점이 이것이다.
 *
 * <p><b>로그인 판정에 쓰이는 값이 두 갈래로 나뉘어 있다</b>는 점이 중요하다 —
 * {@link #status}(계정 자체의 상태)와 {@link #emailVerified}(인증 완료 여부)는 별개이며,
 * 명세도 다른 코드로 구분한다: 미인증은 403 E-AUTH-005, 잠금은 401 E-AUTH-002,
 * 비활성화는 409 E-AUTH-008. 한 필드로 합치면 이 구분이 무너진다.
 *
 * <p>생성 경로는 {@link #createLocal}뿐이다. 소셜 가입(ST-B1-03)은 자격 규칙이 달라
 * ({@code ck_users_credential} — SOCIAL은 provider·provider_user_id 필수) 별도 팩토리를 그때 추가한다.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "login_type", nullable = false, length = 10)
    private LoginType loginType;

    /** 로컬 계정은 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "social_provider", length = 10)
    private SocialProvider socialProvider;

    /**
     * BCrypt 해시(NFR-003). 소셜 전용 계정은 null이며, LOCAL은 {@code ck_users_credential}이 non-null을 강제한다.
     * 원문 비밀번호는 어디에도 남기지 않는다.
     */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "is_email_verified", nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    /** 비활성화 요청 시각(ACCT-04). 30일 복구창의 기산점이며 UTC로 영속된다 — SQ-13. */
    @Column(name = "deactivation_requested_at")
    private Instant deactivationRequestedAt;

    /** 복구 마감 시각(ACCT-04/06). 로그인 시 E-AUTH-008의 {@code recoverableUntil}로 나간다. */
    @Column(name = "scheduled_deletion_at")
    private Instant scheduledDeletionAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected User() {
    }

    /**
     * 로컬 가입 계정 생성(ST-B1-04 · AUTH-03).
     *
     * <p><b>이메일 미인증 상태로 시작한다</b> — 가입 직후에는 로그인이 막히고(403 E-AUTH-005),
     * 인증 메일 확인으로 열린다(AUTH-04). 상태는 ACTIVE인데 이는 "계정이 잠기거나 비활성화되지
     * 않았다"는 뜻이지 "로그인 가능"이라는 뜻이 아니다 — 두 축을 분리해 둔 이유다.
     *
     * @param email        정규화된(소문자·trim) 이메일. 중복 판정은 호출부가 이미 끝냈다
     * @param passwordHash BCrypt 해시. 원문을 넘기면 안 된다
     */
    public static User createLocal(String email, String passwordHash, Instant now) {
        User u = new User();
        u.userId = UUID.randomUUID();
        u.email = email;
        u.passwordHash = passwordHash;
        u.loginType = LoginType.LOCAL;
        u.socialProvider = null;
        u.emailVerified = false;
        u.status = UserStatus.ACTIVE;
        u.createdAt = now;
        return u;
    }

    /** 이메일 인증 완료(AUTH-04) — ②에서 호출된다. */
    public void verifyEmail() {
        this.emailVerified = true;
    }

    /** 비밀번호 교체(AUTH-06 · ACCT-02). 호출부가 기존 세션을 폐기해야 한다(ST-B1-05 AC4). */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public LoginType getLoginType() {
        return loginType;
    }

    public SocialProvider getSocialProvider() {
        return socialProvider;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getDeactivationRequestedAt() {
        return deactivationRequestedAt;
    }

    public Instant getScheduledDeletionAt() {
        return scheduledDeletionAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
