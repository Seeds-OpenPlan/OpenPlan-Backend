package com.openplan.backend.push.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 구독 (기기 주소) — {@code push_subscriptions} 매핑 (ADR-0015 결정 ④). 기기마다 1행({@code endpoint} UNIQUE).
 *
 * <p>같은 endpoint 로 다시 구독 요청이 오면({@link #reassignTo}) 소유자·키를 덮어쓴다 — 사용자가 로그아웃 후
 * 다른 계정으로 로그인한 기기가 이전 계정에 계속 묶여 있으면 남의 계정으로 알림이 간다. endpoint UNIQUE가
 * "그 기기는 지금 어느 계정 것인가"의 유일한 진실이 되도록 재할당을 허용한다.
 */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

    @Id
    @Column(name = "push_subscription_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 2048, updatable = false)
    private String endpoint;

    @Column(nullable = false, length = 255)
    private String p256dh;

    @Column(nullable = false, length = 255)
    private String auth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushPlatform platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    /** JPA 전용. */
    protected PushSubscription() {
    }

    public static PushSubscription create(UUID userId, String endpoint, String p256dh, String auth,
                                          PushPlatform platform, Instant now) {
        PushSubscription s = new PushSubscription();
        s.id = UUID.randomUUID();
        s.userId = userId;
        s.endpoint = endpoint;
        s.p256dh = p256dh;
        s.auth = auth;
        s.platform = platform;
        s.createdAt = now;
        return s;
    }

    /** 같은 기기(endpoint)가 다른 사용자·새 키로 재구독 — 소유자·암호화 키를 갈아끼운다. */
    public void reassignTo(UUID userId, String p256dh, String auth, PushPlatform platform) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.platform = platform;
    }

    public void markSendSucceeded(Instant now) {
        this.lastSuccessAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public PushPlatform getPlatform() {
        return platform;
    }
}
