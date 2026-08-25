package com.openplan.backend.settings.dto;

import com.openplan.backend.availability.dto.AvailabilityView;
import com.openplan.backend.externalcalendar.dto.ExternalConnectionResponse;
import com.openplan.backend.notification.dto.NotificationSettingResponse;
import com.openplan.backend.preference.dto.PreferencesResponse;

import java.util.List;

/**
 * 설정 홈 조립 응답 (D-12 레버 5 · {@code GET /settings}) — openapi 의 4필드와 1:1.
 *
 * <p><b>새로운 값을 만들지 않는다.</b> 네 조각 모두 각자의 엔드포인트가 이미 내주는 것과 같은
 * 표현이다 — 여기서 모양을 바꾸면 같은 데이터가 화면마다 다르게 보이고, 그 차이는 저장할 때
 * 드러난다. 이 응답의 값은 <b>왕복 횟수</b>에 있지 표현에 있지 않다.
 */
public record SettingsHomeResponse(
        AvailabilityView availability,
        PreferencesResponse preferences,
        List<ExternalConnectionResponse> connections,
        List<NotificationSettingResponse> notificationSettings
) {
}
