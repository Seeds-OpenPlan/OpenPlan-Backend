package com.openplan.backend.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 일정 (PLAN-08/17). V1 baseline {@code schedules} 매핑 — 프로젝트와 무관한 개인 일정(태스크와 별개).
 *
 * <p>이번 스토리(ST-B2-08)에서는 SCHEDULE 블록 배치와 <b>함께 생성</b>된다(PLAN-08: 제목·예상시간·우선순위·메모 입력).
 * 단독 편집/삭제(PLAN-17/18)는 후속. start/end는 배치 시각(블록과 동일). version 낙관락, createdAt은 UserClock 주입.
 */
@Getter
@Entity
@Table(name = "schedules")
public class Schedule {

    @Id
    @Column(name = "schedule_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    private Integer priority;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(columnDefinition = "text")
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected Schedule() {
    }

    /**
     * 생성 규정 (PLAN-08): status=ACTIVE, version=0. title은 서비스가 trim·검증한 값,
     * estimatedMinutes·priority는 검증 통과값(null 허용), createdAt은 UserClock 주입값(P-2).
     */
    public Schedule(UUID userId, String title, Integer estimatedMinutes, Integer priority,
                    Instant startAt, Instant endAt, String memo, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.estimatedMinutes = estimatedMinutes;
        this.priority = priority;
        this.startAt = startAt;
        this.endAt = endAt;
        this.memo = memo;
        this.status = ScheduleStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    /**
     * 일정 편집 (PLAN-17) — 제목·예상시간·우선순위·메모를 검증 통과값으로 갈아끼운다. 시각(start/end)·상태는
     * 편집으로 못 바꾼다(시각 변경은 블록 이동 소관). version은 flush 시 {@code @Version}이 증가시킨다(낙관락).
     */
    public void edit(String title, Integer estimatedMinutes, Integer priority, String memo) {
        this.title = title;
        this.estimatedMinutes = estimatedMinutes;
        this.priority = priority;
        this.memo = memo;
    }

    /**
     * 외부 캘린더에서 고쳐진 것을 되받는다 (#69 D4).
     *
     * <p><b>{@code edit()} 과 나눠 둔 이유</b> — 그쪽은 «시각은 편집으로 못 바꾼다(블록 이동
     * 소관)» 는 규약을 갖는다. 그 규약은 <b>OpenPlan 화면 안에서</b> 옳다. 외부 캘린더에서는
     * 사용자가 일정을 끌어 옮기는 것이 곧 시각 변경이고, 그것을 되받으려면 여기를 지나야 한다.
     * 같은 메서드에 합치면 화면 쪽 규약이 조용히 풀린다.
     *
     * <p>호출부는 <b>블록도 함께</b> 옮겨야 한다 — 일정만 바꾸면 주간 계획에는 옛 자리가 남는다.
     */
    public void relocatedFromExternal(String title, Instant startAt, Instant endAt) {
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
    }
}
