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

    /** {@code name} 컬럼 길이(VARCHAR(50)). 잠정 이름 생성 시 이 길이로 자른다. */
    public static final int NAME_MAX = 50;

    /** 스키마 기본값과 동일 — 온보딩 전까지의 잠정 시간대. */
    private static final String DEFAULT_TIMEZONE = "Asia/Seoul";

    /**
     * 가입 시 프로필 행 생성(ST-B1-04).
     *
     * <p><b>왜 가입 시점에 만드는가</b>: 프로필을 만드는 엔드포인트가 계약에 없다. ONB-02는
     * {@code PATCH /users/me/profile}(부분 수정)뿐이고 {@link UserProfileService}는 행이 없으면
     * E-COM-004를 던진다 — 가입이 행을 만들어 두지 않으면 온보딩 자체가 시작되지 않는다.
     * 이 클래스 javadoc이 "신규 프로필 생성은 가입 스토리 ST-B1-04 소관"이라 예고한 자리다.
     *
     * <p>🔴 <b>{@code name}은 잠정값이다.</b> 가입 요청에 이름 자리가 없는데(계약 확정) 컬럼은 NOT NULL이라,
     * 이메일 아이디부를 잠정 이름으로 넣고 ONB-02에서 사용자가 덮어쓴다. <b>계약에 근거가 없는 리드 판단</b>이므로
     * 팀 확인 대상이다 — 대안은 빈 문자열 저장 후 응답에서 null로 매핑하는 방식이며, 바꾸려면 이 메서드 한 곳만 고치면 된다.
     *
     * @param provisionalName 이미 잘라 낸 잠정 이름(공백이면 호출부가 대체값을 정한다)
     */
    public static UserProfile createInitial(UUID userId, String provisionalName) {
        UserProfile p = new UserProfile();
        p.profileId = UUID.randomUUID();
        p.userId = userId;
        p.name = provisionalName;
        p.purpose = null;
        p.timezone = DEFAULT_TIMEZONE;
        p.weekStartDay = Weekday.MON;
        return p;
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
