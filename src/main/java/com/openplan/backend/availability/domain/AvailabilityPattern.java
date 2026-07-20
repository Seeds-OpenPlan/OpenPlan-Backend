package com.openplan.backend.availability.domain;

import com.openplan.backend.common.Weekday;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.util.UUID;

/**
 * 가용 시간 패턴 엔티티 — {@code availability_patterns}. 요일당 1행(UQ user_id+weekday, A4),
 * PUT 전체 교체 방식이라 수정 대신 삭제-재삽입한다(세터 없음).
 *
 * <p>DB 제약과 이중 방어: start_time &lt; end_time(ck_availability_range) · 5분 단위(ck_availability_step).
 * 서버는 저장 전에 같은 조건을 검증해 422 E-COM-009로 안내한다(NFR-010/009).
 */
@Entity
@Table(name = "availability_patterns")
public class AvailabilityPattern {

    @Id
    @Column(name = "availability_pattern_id", nullable = false, updatable = false)
    private UUID availabilityPatternId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "weekday", nullable = false, length = 3)
    private Weekday weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** JPA 전용 기본 생성자. */
    protected AvailabilityPattern() {
    }

    /**
     * 신규 행 생성(전체 교체 저장용). id는 애플리케이션에서 부여한다(DB default gen_random_uuid 대신
     * 명시 삽입 — 삽입 후 반환값 재조회를 피하기 위함). created_at은 미매핑이라 DB DEFAULT now()가 채운다.
     */
    public static AvailabilityPattern create(UUID userId, Weekday weekday,
                                             LocalTime startTime, LocalTime endTime, boolean active) {
        AvailabilityPattern p = new AvailabilityPattern();
        p.availabilityPatternId = UUID.randomUUID();
        p.userId = userId;
        p.weekday = weekday;
        p.startTime = startTime;
        p.endTime = endTime;
        p.active = active;
        return p;
    }

    public UUID getAvailabilityPatternId() {
        return availabilityPatternId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Weekday getWeekday() {
        return weekday;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public boolean isActive() {
        return active;
    }
}
