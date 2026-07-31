package com.openplan.backend.user.repository;

import com.openplan.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 계정 루트 리포지토리. 07은 {@code findById}(내 계정 조회)만 사용한다.
 */
public interface UserRepository extends JpaRepository<User, UUID> {
}
