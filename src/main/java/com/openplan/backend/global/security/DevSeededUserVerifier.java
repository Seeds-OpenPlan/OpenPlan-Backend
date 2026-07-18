package com.openplan.backend.global.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * dev-auth 스텁의 주체 존재 검증 (api-contracts §4.1-4: 미시드 UUID → 401 E-COM-002).
 *
 * <p>존재하지 않는 사용자를 주입하면 그 뒤 도메인 쿼리는 전부 "빈 결과"로 조용히 성공한다 —
 * 개발자는 데이터가 없는 건지 인증이 틀린 건지 구분할 수 없다. 운영에서 JWT가 무효 토큰을
 * 401로 끊는 것과 같은 표면을 스텁 기간에도 유지하려는 것이 이 검증의 목적이다.
 *
 * <p>JPA 엔티티가 아니라 {@link JdbcTemplate}를 쓰는 이유: {@code users} 엔티티는 4주차
 * 인증 실구현(ST-B1-02~06)에서 정의된다. 스텁 검증 하나 때문에 엔티티를 먼저 만들면
 * 그때 형태가 굳어버린다. 이 클래스는 스텁과 함께 소멸한다.
 */
@Component
public class DevSeededUserVerifier {

    private static final Logger log = LoggerFactory.getLogger(DevSeededUserVerifier.class);

    private static final String EXISTS_SQL = "SELECT EXISTS (SELECT 1 FROM users WHERE user_id = ?)";

    private final JdbcTemplate jdbcTemplate;

    public DevSeededUserVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 시드된 사용자면 true. dev 프로파일 한정 호출이므로 요청당 1회 조회는 감수한다
     * (캐시는 시드 변경 시 stale 위험만 추가한다).
     */
    public boolean exists(UUID userId) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(EXISTS_SQL, Boolean.class, userId));
        } catch (DataAccessException ex) {
            // DB 접근 실패를 인증 실패로 되돌려주는 건 정확하지 않다. 다만 이 필터는 서블릿
            // 체인 앞단이라 GlobalExceptionHandler가 못 받는다 → 401로 끊되 원인은 ERROR 로그로 남긴다.
            log.error("dev 사용자 존재 검증 실패 (DB 접근 오류) userId={} — 401로 처리한다", userId, ex);
            return false;
        }
    }
}
