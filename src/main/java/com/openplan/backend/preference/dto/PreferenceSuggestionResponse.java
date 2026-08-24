package com.openplan.backend.preference.dto;

import com.openplan.backend.availability.dto.AvailabilityPatternDto;

import java.util.List;

/**
 * 규칙 기반 기본값 제안 (SS-14 · {@code GET /users/me/preference-suggestions}) — openapi
 * {@code PreferenceSuggestion} 과 1:1.
 *
 * <p><b>제안만 한다. 적용은 사용자가 PUT 으로 한다(C-2).</b> 이 응답이 설정을 바꾸지 않는다는 것이
 * 이 엔드포인트의 존재 이유다 — 자동으로 바꾸면 사용자는 자기가 안 바꾼 값이 바뀐 것을 보게 된다.
 *
 * <p>{@code reason} 은 <b>규칙 서술</b>이다. "AI 가 분석했습니다" 류의 표현을 쓰지 않는다(P4) —
 * 계산은 중앙값·빈도이고, 그렇게 말해야 사용자가 값을 검증할 수 있다.
 */
public record PreferenceSuggestionResponse(
        Integer suggestedEstimatedMinutes,
        String suggestedReplanStrategy,
        List<AvailabilityPatternDto> suggestedAvailabilities,
        String reason
) {
}
