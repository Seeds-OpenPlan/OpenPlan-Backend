package com.openplan.backend.global.time;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * {@link UserClock} 기본 구현 — {@code user_profiles.timezone}을 JdbcTemplate로 조회한다.
 *
 * <p>global에 두는 이유(선례 일치): {@code user_profiles}는 타 도메인(BE-1) 테이블이지만, 이미
 * {@link com.openplan.backend.global.security.DevSeededUserVerifier}가 같은 방식으로 {@code users}를
 * SQL로 접촉한다("소유 엔티티가 정의되기 전까지 잠정 SQL 접촉"). timezone 해석은 전 도메인 공통 관심사라
 * 도메인별 중복 구현(경계 드리프트)을 막기 위해 global 공용 seam으로 둔다. 행 부재/미설정/미인식 zone →
 * {@code Asia/Seoul} fallback(Q-G).
 */
@Component
public class JdbcUserClock implements UserClock {

    /** Q-G fallback — 프로필 미생성(온보딩 전) 사용자 포함. */
    static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public LocalDate todayOf(UUID userId) {
        ZoneId zone = resolveZone(userId);
        return LocalDate.now(zone);
    }

    private ZoneId resolveZone(UUID userId) {
        String tz;
        try {
            tz = jdbcTemplate.queryForObject(
                    "SELECT timezone FROM user_profiles WHERE user_id = ?",
                    String.class, userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return FALLBACK_ZONE; // 프로필 행 부재
        }
        if (tz == null || tz.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(tz.trim());
        } catch (java.time.DateTimeException ex) {
            return FALLBACK_ZONE; // 미인식 zone 문자열
        }
    }
}
