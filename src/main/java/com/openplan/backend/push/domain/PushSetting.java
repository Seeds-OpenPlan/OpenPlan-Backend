package com.openplan.backend.push.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 푸시 설정 (ADR-0015 결정 ⑥) — {@code push_settings} 매핑. 사용자당 1행, PK가 바로 {@code user_id}다.
 *
 * <p><b>행이 없으면 전부 꺼짐</b> — DDL default와 같은 값이라 {@code notification_settings}(ADR-0014)처럼
 * NOT NULL FK 충족을 위한 지연 시드가 필요 없다. 최초 조회는 행 없이 기본값 DTO를 만들어 반환하고,
 * 저장(PUT) 시점에만 upsert한다({@link com.openplan.backend.push.service.PushSettingService}).
 *
 * <p>알림 설정({@code notification_settings}, 5유형)과 섞지 않는다 — 대상도 축도 다르다(ADR-0015 결정 ⑥).
 */
@Entity
@Table(name = "push_settings")
public class PushSetting {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "task_enabled", nullable = false)
    private boolean taskEnabled;

    @Column(name = "fixed_schedule_enabled", nullable = false)
    private boolean fixedScheduleEnabled;

    @Column(name = "schedule_enabled", nullable = false)
    private boolean scheduleEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected PushSetting() {
    }

    /** 최초 저장(PUT) — 기본값 전부 꺼짐 위에 요청값을 얹는다. */
    public static PushSetting create(UUID userId, boolean taskEnabled, boolean fixedScheduleEnabled,
                                     boolean scheduleEnabled, Instant now) {
        PushSetting s = new PushSetting();
        s.userId = userId;
        s.taskEnabled = taskEnabled;
        s.fixedScheduleEnabled = fixedScheduleEnabled;
        s.scheduleEnabled = scheduleEnabled;
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    public void change(boolean taskEnabled, boolean fixedScheduleEnabled, boolean scheduleEnabled, Instant now) {
        this.taskEnabled = taskEnabled;
        this.fixedScheduleEnabled = fixedScheduleEnabled;
        this.scheduleEnabled = scheduleEnabled;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isTaskEnabled() {
        return taskEnabled;
    }

    public boolean isFixedScheduleEnabled() {
        return fixedScheduleEnabled;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }
}
