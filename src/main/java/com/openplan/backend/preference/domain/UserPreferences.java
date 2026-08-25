package com.openplan.backend.preference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자 동작 기본값 (FIX-10·11·12) — V1 baseline {@code user_preferences} 매핑.
 *
 * <p><b>프로필과 분리돼 있다</b>(ERD §7 분기 2). 프로필은 정체성(이름·타임존)이고 여기는 동작
 * 기본값이다. 값이 전부 nullable 인 것은 "정하지 않음" 이 유효한 상태이기 때문이다 — 그때는
 * 화면이 서버 기본값이 아니라 <b>비어 있음</b>을 보여야 한다.
 *
 * <p>{@code weeklyAvailableMinutes} 는 <b>요일 창의 합계가 아니다.</b> 합계는
 * {@code availability_patterns} 에서 계산되는 참고용 총량이고, 이것은 사용자가 직접 정하는 주간
 * 목표다(오너 결정 2026-07-25). 두 값을 같은 이름으로 부르면 화면이 어느 쪽을 말하는지 알 수 없다.
 */
@Getter
@Entity
@Table(name = "user_preferences")
public class UserPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** 5분 단위·양수 (ck_user_pref_estimated). */
    @Column(name = "default_estimated_minutes")
    private Integer defaultEstimatedMinutes;

    /** ck_user_pref_replan 의 4값 중 하나. */
    @Column(name = "default_replan_strategy", length = 30)
    private String defaultReplanStrategy;

    /** 5분 단위·양수 (ck_user_pref_weekly_available). */
    @Column(name = "weekly_available_minutes")
    private Integer weeklyAvailableMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserPreferences() {
    }

    /** 처음 저장할 때 만든다 — 가입 시 미리 만들지 않는다(안 쓰는 사용자의 빈 행을 남기지 않기 위해). */
    public UserPreferences(UUID userId, Instant now) {
        this.userId = userId;
        this.updatedAt = now;
        this.createdAt = now;
    }

    /**
     * 전체 교체 (PUT). 담겨 오지 않은 값은 null 로 지워진다 — 계약이 PATCH 가 아니라 PUT 이라
     * "빈 값으로 되돌리기" 를 표현할 방법이 이것뿐이다.
     */
    public void replace(Integer defaultEstimatedMinutes, String defaultReplanStrategy,
                        Integer weeklyAvailableMinutes, Instant now) {
        this.defaultEstimatedMinutes = defaultEstimatedMinutes;
        this.defaultReplanStrategy = defaultReplanStrategy;
        this.weeklyAvailableMinutes = weeklyAvailableMinutes;
        this.updatedAt = now;
    }
}
