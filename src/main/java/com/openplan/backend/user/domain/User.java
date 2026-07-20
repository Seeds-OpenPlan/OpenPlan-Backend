package com.openplan.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 계정 루트 엔티티 — {@code users} 테이블(V1 baseline). ST-B1-07은 프로필 응답 조립에 필요한
 * 최소 컬럼만 매핑한다(email·login_type·social_provider). status·password_hash 등은 인증·계정
 * 스토리(ST-B1-02/04/06)에서 필요 시 매핑을 확장한다 — 매핑되지 않은 컬럼은 {@code ddl-auto: validate}에
 * 무해하다(존재하는 컬럼만 검증).
 *
 * <p>07 범위에서 이 엔티티는 읽기 전용(세터 없음) — 계정 상태 변경은 별도 스토리 소관.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "login_type", nullable = false, length = 10)
    private LoginType loginType;

    /** 로컬 계정은 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "social_provider", length = 10)
    private SocialProvider socialProvider;

    /** JPA 전용 기본 생성자. */
    protected User() {
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public LoginType getLoginType() {
        return loginType;
    }

    public SocialProvider getSocialProvider() {
        return socialProvider;
    }
}
