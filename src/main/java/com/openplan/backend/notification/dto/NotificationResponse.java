package com.openplan.backend.notification.dto;

import com.openplan.backend.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

/**
 * 알림 응답 — 정본 openapi {@code Notification} 스키마와 1:1.
 *
 * <p>{@code title}·{@code routePath}는 <b>생성 시점에 박제된 값을 그대로</b> 싣는다. 여기서 다시
 * 조립하지 않는다(ADR-0014).
 */
public record NotificationResponse(UUID notificationId, String notificationType, String title,
                                   String routePath, Instant readAt, Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getNotificationId(),
                n.getNotificationType().name(),
                n.getTitle(),
                n.getRoutePath(),
                n.getReadAt(),
                n.getCreatedAt());
    }
}
