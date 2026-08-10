package com.openplan.backend.user.repository;

import com.openplan.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 계정 루트 리포지토리. 07(프로필)은 {@code findById}만 쓰고, ST-B1-02/04(로그인·가입)가 이메일 조회를 더한다.
 *
 * <p>이메일은 <b>정규화된 형태(소문자·trim)로만</b> 조회·저장한다. {@code users.email}이 UNIQUE이지만
 * 대소문자를 구분하므로, 정규화를 거치지 않으면 {@code A@b.com}과 {@code a@b.com}이 별개 계정이 된다.
 * 정규화 지점은 가입·로그인 서비스 한 곳이다.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** 로그인 입구(AUTH-01). 계정 부재와 비밀번호 불일치는 <b>같은 응답</b>으로 합쳐진다 — E-AUTH-001 열거 방지. */
    Optional<User> findByEmail(String email);

    /** 가입 중복 판정(AUTH-03 → 409 E-AUTH-003). 경합은 UNIQUE 제약이 최종적으로 막는다. */
    boolean existsByEmail(String email);
}
