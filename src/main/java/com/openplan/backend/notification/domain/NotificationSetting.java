package com.openplan.backend.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 알림 설정 엔티티 — {@code notification_settings}. 사용자 × 유형 1행({@code ux_noti_setting}).
 *
 * <p><b>꺼짐은 필터가 아니라 미생성이다</b>(ST-B1-12 AC1 · ADR-0014). {@code isEnabled=false}면
 * 판정 자체를 건너뛰어 행을 만들지 않는다 — 껐다 켜도 그 사이의 알림은 되살아나지 않는다.
 */
@Entity
@Table(name = "notification_settings")
public class NotificationSetting {

    @Id
    @Column(name = "notification_setting_id", nullable = false, updatable = false)
    private UUID notificationSettingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false, length = 30)
    private NotificationType notificationType;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationSetting() {
    }

    /** 지연 시드 — 최초 조회·판정 시 5행을 기본 켜짐으로 만든다(DDL default 와 같은 값). */
    public NotificationSetting(UUID userId, NotificationType type, Instant now) {
        this.notificationSettingId = UUID.randomUUID();
        this.userId = userId;
        this.notificationType = type;
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeEnabled(boolean enabled, Instant now) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            this.updatedAt = now;
        }
    }

    public UUID getNotificationSettingId() {
        return notificationSettingId;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
