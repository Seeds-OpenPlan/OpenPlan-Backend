package com.openplan.backend.preference.dto;

/**
 * 기본 설정 저장 요청 (정본 openapi.yaml {@code Preferences}) — PUT 이라 전체 교체다.
 *
 * <p>세 값 모두 null 허용이다. null 은 "설정하지 않음" 이고 서버 기본값으로 대체되지 않는다 —
 * 화면이 "비어 있음" 을 보여야 사용자가 자기가 정한 적 없다는 것을 안다.
 *
 * <p>값 규칙(5분 단위·전략 enum)은 {@code PreferencesValidator} 가 422 로 판정한다. 어노테이션으로
 * 나누지 않은 것은 DB CHECK 와 같은 규칙을 한 곳에서 읽히게 하기 위해서다.
 */
public record PreferencesRequest(
        Integer defaultEstimatedMinutes,
        String defaultReplanStrategy,
        Integer weeklyAvailableMinutes) {
}
