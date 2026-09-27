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
 *
 * <p><b>{@code writeCalendarId}·{@code canWrite} 는 화면이 «왜 안 나가는가» 를 말하기 위한 것이다
 * (이슈 #69).</b> 아웃바운드는 둘 중 하나라도 비면 예외 없이 조용히 0건인데, 그 침묵의 이유를
 * 응답에 싣지 않으면 사용자는 «연동은 켜져 있는데 아무 일도 안 일어나는» 화면만 본다.
 */
public record ExternalConnectionResponse(
        UUID connectionId,
        String provider,
        String accountIdentifier,
        String syncMode,
        String status,
        Instant connectedAt,
        String writeCalendarId,
        boolean canWrite,
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
                connection.getWriteCalendarId(),
                connection.canWrite(),
                selections.stream()
                        .map(s -> new SelectedCalendarDto(s.getExternalCalendarId(), s.getCalendarName()))
                        .toList());
    }
}
