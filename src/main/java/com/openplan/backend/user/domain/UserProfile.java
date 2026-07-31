package com.openplan.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 사용자 프로필 엔티티 — {@code user_profiles} 테이블(V1 baseline, users와 1:1 · user_id UNIQUE).
 * 이름·사용 목적·시간대·주 시작 요일을 보유한다(ACCT-01 · ONB-02).
 *
 * <p>07 범위에서 신규 프로필 생성은 하지 않는다(가입 스토리 ST-B1-04 소관) — 기존 행을 읽고
 * 부분 수정만 한다. 수정은 {@link #changeName}/{@link #changePurpose}/{@link #changeTimezone}/
 * {@link #changeWeekStartDay}로만 이뤄지고, 트랜잭션 커밋 시 JPA 더티 체킹으로 UPDATE 된다.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 사용 목적(ONB-02) — nullable. */
    @Column(name = "purpose", length = 100)
    private String purpose;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "week_start_day", nullable = false, length = 3)
    private Weekday weekStartDay;

    /** JPA 전용 기본 생성자. */
    protected UserProfile() {
    }

    public UUID getProfileId() {
        return profileId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getTimezone() {
        return timezone;
    }

    public Weekday getWeekStartDay() {
        return weekStartDay;
    }

    // ---- 부분 수정 (PATCH /users/me/profile) — 제공된 필드만 서비스가 선택 호출 ----

    public void changeName(String name) {
        this.name = name;
    }

    public void changePurpose(String purpose) {
        this.purpose = purpose;
    }

    public void changeTimezone(String timezone) {
        this.timezone = timezone;
    }

    public void changeWeekStartDay(Weekday weekStartDay) {
        this.weekStartDay = weekStartDay;
    }
}
