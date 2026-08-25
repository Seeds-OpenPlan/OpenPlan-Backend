package com.openplan.backend.notification.dto;

import com.openplan.backend.notification.domain.NotificationSetting;

/** 알림 설정 응답 — 정본 {@code NotificationSetting} 스키마와 1:1 (NOTI-01). */
public record NotificationSettingResponse(String notificationType, boolean isEnabled) {

    public static NotificationSettingResponse from(NotificationSetting s) {
        return new NotificationSettingResponse(s.getNotificationType().name(), s.isEnabled());
    }
}
