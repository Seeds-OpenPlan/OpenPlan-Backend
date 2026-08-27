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
 * 알림 엔티티 — {@code notifications} (ST-B1-12 · NOTI-02/03/04 · ADR-0014).
 *
 * <p><b>title 과 routePath 는 생성 시점 스냅샷이다.</b> 조회할 때 조립하지 않는다 —
 * "'{태스크}' 마감이 3일 남았습니다"의 3은 날이 갈수록 달라지고, 태스크 제목이 바뀌면 과거 알림 문구까지
 * 소급해 바뀐다. 알림은 <b>그때 그런 일이 있었다</b>는 기록이므로 만들 때의 사실로 박제한다.
 *
 * <p>related FK 셋은 <b>유형마다 하나만</b> 채워진다(DDL 이 전부 nullable). 어느 것을 채우는지는
 * ADR-0014 유형 표가 정본이며, 이 엔티티는 그 선택을 강제하지 않는다 — 판정 서비스의 몫이다.
 *
 * <p>{@code is_active} 는 쓰지 않는다(항상 true). 논리모델 승계 컬럼이나 소비 US 가 없고,
 * "꺼짐"은 soft-off 가 아니라 미생성으로 구현된다(ADR-0014).
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "notification_setting_id", nullable = false, updatable = false)
    private UUID notificationSettingId;

    @Column(name = "related_task_id", updatable = false)
    private UUID relatedTaskId;

    @Column(name = "related_weekly_plan_id", updatable = false)
    private UUID relatedWeeklyPlanId;

    @Column(name = "related_support_ticket_id", updatable = false)
    private UUID relatedSupportTicketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false, length = 30)
    private NotificationType notificationType;

    @Column(name = "title", nullable = false, updatable = false, length = 200)
    private String title;

    @Column(name = "route_path", updatable = false, length = 255)
    private String routePath;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    private Notification(UUID userId, UUID settingId, NotificationType type,
                         String title, String routePath, Instant now) {
        this.notificationId = UUID.randomUUID();
        this.userId = userId;
        this.notificationSettingId = settingId;
        this.notificationType = type;
        this.title = title;
        this.routePath = routePath;
        this.readAt = null;
        this.active = true;
        this.createdAt = now;
    }

    /** 태스크 연결 — DEADLINE_SOON(태스크당 1건). */
    public static Notification forTask(UUID userId, UUID settingId, NotificationType type,
                                       String title, String routePath, UUID taskId, Instant now) {
        Notification n = new Notification(userId, settingId, type, title, routePath, now);
        n.relatedTaskId = taskId;
        return n;
    }

    /** 주간 계획 연결 — TODAY_TASKS · PLAN_UNSAVED. */
    public static Notification forWeeklyPlan(UUID userId, UUID settingId, NotificationType type,
                                             String title, String routePath, UUID weeklyPlanId, Instant now) {
        Notification n = new Notification(userId, settingId, type, title, routePath, now);
        n.relatedWeeklyPlanId = weeklyPlanId;
        return n;
    }

    /** 문의 연결 — SUPPORT_ANSWERED. 본인 문의 상세로만 보낸다(NFR-030). */
    public static Notification forSupportTicket(UUID userId, UUID settingId, NotificationType type,
                                                String title, String routePath, UUID ticketId, Instant now) {
        Notification n = new Notification(userId, settingId, type, title, routePath, now);
        n.relatedSupportTicketId = ticketId;
        return n;
    }

    /** 연결 없음 — RETROSPECT(통계 화면으로만 보낸다. related 전부 null 은 DDL 허용). */
    public static Notification standalone(UUID userId, UUID settingId, NotificationType type,
                                          String title, String routePath, Instant now) {
        return new Notification(userId, settingId, type, title, routePath, now);
    }

    /** 읽음 처리 — 멱등. 이미 읽은 알림의 시각은 덮어쓰지 않는다(최초 읽은 시점이 사실이다). */
    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public String getTitle() {
        return title;
    }

    public String getRoutePath() {
        return routePath;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
