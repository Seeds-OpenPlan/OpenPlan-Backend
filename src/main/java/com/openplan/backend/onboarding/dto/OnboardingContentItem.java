package com.openplan.backend.onboarding.dto;

/**
 * 온보딩 콘텐츠 카드 한 장 — {@code GET /onboarding/contents} 응답 요소(openapi 스키마 1:1).
 * 문구는 서버 리소스({@code resources/onboarding/contents.json})에서 로드하며 여기서 조립하지 않는다.
 *
 * @param imageUrl 이미지 없으면 null(텍스트 전용 카드)
 */
public record OnboardingContentItem(int order, String title, String body, String imageUrl) {
}
