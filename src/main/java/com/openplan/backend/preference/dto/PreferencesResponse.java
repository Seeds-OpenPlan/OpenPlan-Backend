package com.openplan.backend.preference.dto;

import com.openplan.backend.preference.domain.UserPreferences;

/**
 * 기본 설정 응답 (정본 openapi.yaml {@code Preferences}).
 *
 * <p>저장된 적이 없으면 {@link #empty()} — 셋 다 null 이다. 404 를 주지 않는 이유는 "설정을 안 했다"가
 * 정상 상태이기 때문이다. 화면은 이 응답으로 빈 폼을 그린다.
 */
public record PreferencesResponse(
        Integer defaultEstimatedMinutes,
        String defaultReplanStrategy,
        Integer weeklyAvailableMinutes) {

    public static PreferencesResponse from(UserPreferences p) {
        return new PreferencesResponse(p.getDefaultEstimatedMinutes(),
                p.getDefaultReplanStrategy(), p.getWeeklyAvailableMinutes());
    }

    public static PreferencesResponse empty() {
        return new PreferencesResponse(null, null, null);
    }
}
