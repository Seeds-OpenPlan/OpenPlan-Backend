package com.openplan.backend.onboarding.dto;

import com.openplan.backend.onboarding.domain.OnboardingProgress;

import java.util.UUID;

/**
 * 온보딩 진행 상태 응답 — openapi {@code OnboardingProgress} 스키마와 1:1.
 * {@code tutorialSampleProjectId}는 서버 관리값으로 읽기 전용 노출(잔존 시 non-null → TUT-09 확인 재료).
 */
public record OnboardingProgressResponse(
        boolean introDone,
        boolean profileDone,
        boolean availabilityDone,
        boolean fixedScheduleDone,
        boolean tutorialDone,
        boolean calendarSyncDone,
        UUID tutorialSampleProjectId
) {

    public static OnboardingProgressResponse from(OnboardingProgress p) {
        return new OnboardingProgressResponse(
                p.isIntroDone(),
                p.isProfileDone(),
                p.isAvailabilityDone(),
                p.isFixedScheduleDone(),
                p.isTutorialDone(),
                p.isCalendarSyncDone(),
                p.getTutorialSampleProjectId());
    }
}
