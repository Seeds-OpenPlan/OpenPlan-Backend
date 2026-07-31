package com.openplan.backend.onboarding.domain;

/**
 * 온보딩 콘텐츠 단계 — {@code GET /onboarding/contents?step=}의 파라미터(openapi step enum과 1:1).
 *
 * <p>INTRO=소개 2장(ONB-01) · AVAILABILITY_GUIDE=가용시간 안내(ONB-03) ·
 * FIXED_SCHEDULE_GUIDE=고정일정 안내(ONB-05) · TUTORIAL=튜토리얼(실 UI 코치마크라 정적 콘텐츠 없음 → 빈 목록).
 * 문구 정본: ui-spec §ONB 표 + us-decisions §4.3(P4 규칙 기반 명칭, "AI기능" 문자열 0건).
 */
public enum OnboardingStep {
    INTRO,
    AVAILABILITY_GUIDE,
    FIXED_SCHEDULE_GUIDE,
    TUTORIAL
}
