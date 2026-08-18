package com.openplan.backend.notification.domain;

/**
 * 알림 유형 5종 — {@code ck_noti_type}·{@code ck_noti_setting_type} CHECK 제약과 1:1 (ADR-0014).
 *
 * <p>설정({@code notification_settings})도 같은 5종을 쓴다. 유형이 늘면 DDL CHECK 두 곳과
 * openapi {@code NotificationSetting.notificationType} enum 을 함께 고쳐야 한다.
 */
public enum NotificationType {
    DEADLINE_SOON,
    TODAY_TASKS,
    PLAN_UNSAVED,
    RETROSPECT,
    SUPPORT_ANSWERED
}
