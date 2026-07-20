package com.openplan.backend.user.repository;

import com.openplan.backend.user.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 프로필 리포지토리. 프로필은 user_id로 조회한다(1:1 · UNIQUE) — PK(profile_id)가 아니라
 * 인증 주체 UUID로 스코핑하기 위함.
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByUserId(UUID userId);
}
