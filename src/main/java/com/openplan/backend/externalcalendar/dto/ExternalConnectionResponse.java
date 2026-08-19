package com.openplan.backend.externalcalendar.dto;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarSelection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 연동 응답 — openapi {@code ExternalConnection}.
 *
 * <p>🔴 {@code status}는 저장값이 아니라 <b>계약 어휘</b>({@code CONNECTED}/{@code DISABLED})다.
 * 토큰 계열 필드는 어떤 경우에도 여기 실리지 않는다.
 */
public record ExternalConnectionResponse(
        UUID connectionId,
        String provider,
        String accountIdentifier,
        String syncMode,
        String status,
        Instant connectedAt,
        List<SelectedCalendarDto> selectedCalendars) {

    public static ExternalConnectionResponse of(ExternalCalendarConnection connection,
                                                List<ExternalCalendarSelection> selections) {
        return new ExternalConnectionResponse(
                connection.getId(),
                connection.getProvider().name(),
                connection.getAccountIdentifier(),
                connection.getSyncMode().name(),
                connection.getStatus().apiValue(),
                connection.getConnectedAt(),
                selections.stream()
                        .map(s -> new SelectedCalendarDto(s.getExternalCalendarId(), s.getCalendarName()))
                        .toList());
    }
}
