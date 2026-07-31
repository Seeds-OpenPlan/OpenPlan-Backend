package com.openplan.backend.onboarding.repository;

import com.openplan.backend.onboarding.domain.OnboardingProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 온보딩 진행 리포지토리. user_id가 PK이므로 {@code findById(userId)}로 조회한다.
 */
public interface OnboardingProgressRepository extends JpaRepository<OnboardingProgress, UUID> {
}
