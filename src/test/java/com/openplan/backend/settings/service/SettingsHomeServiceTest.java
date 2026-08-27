package com.openplan.backend.settings.service;

import com.openplan.backend.availability.dto.AvailabilityView;
import com.openplan.backend.availability.service.AvailabilityService;
import com.openplan.backend.externalcalendar.dto.ExternalConnectionResponse;
import com.openplan.backend.externalcalendar.service.ExternalCalendarService;
import com.openplan.backend.notification.dto.NotificationSettingResponse;
import com.openplan.backend.notification.service.NotificationSettingService;
import com.openplan.backend.preference.dto.PreferencesResponse;
import com.openplan.backend.preference.service.PreferencesService;
import com.openplan.backend.settings.dto.SettingsHomeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 설정 홈 조립 단위 테스트(D-12 레버 5).
 *
 * <p>고정하는 것은 <b>조립 계약</b>이다 — 네 원천을 그대로 싣는가, 그리고 한 조각이 실패할 때
 * 부분 응답으로 새지 않는가. 각 조각의 내용 규칙은 그쪽 테스트 소관이다.
 */
@ExtendWith(MockitoExtension.class)
class SettingsHomeServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    @Mock private AvailabilityService availabilityService;
    @Mock private PreferencesService preferencesService;
    @Mock private ExternalCalendarService externalCalendarService;
    @Mock private NotificationSettingService notificationSettingService;

    private SettingsHomeService service;

    @BeforeEach
    void setUp() {
        service = new SettingsHomeService(availabilityService, preferencesService,
                externalCalendarService, notificationSettingService);
    }

    @Test
    @DisplayName("네 원천을 그대로 실어 한 응답으로 묶는다 — 값을 새로 만들지 않는다")
    void assemblesAllFourSources() {
        AvailabilityView availability = mock(AvailabilityView.class);
        PreferencesResponse preferences = mock(PreferencesResponse.class);
        List<ExternalConnectionResponse> connections = List.of(mock(ExternalConnectionResponse.class));
        List<NotificationSettingResponse> notifications = List.of(mock(NotificationSettingResponse.class));

        when(availabilityService.getMyAvailabilities(USER_ID)).thenReturn(availability);
        when(preferencesService.get(USER_ID)).thenReturn(preferences);
        when(externalCalendarService.list(USER_ID)).thenReturn(connections);
        when(notificationSettingService.getSettings(USER_ID)).thenReturn(notifications);

        SettingsHomeResponse res = service.get(USER_ID);

        assertThat(res.availability()).isSameAs(availability);
        assertThat(res.preferences()).isSameAs(preferences);
        assertThat(res.connections()).isSameAs(connections);
        assertThat(res.notificationSettings()).isSameAs(notifications);
    }

    @Test
    @DisplayName("모든 조회가 같은 주체(userId)로만 스코핑된다")
    void scopesEverythingToTheSameSubject() {
        when(availabilityService.getMyAvailabilities(USER_ID)).thenReturn(mock(AvailabilityView.class));
        when(preferencesService.get(USER_ID)).thenReturn(mock(PreferencesResponse.class));
        when(externalCalendarService.list(USER_ID)).thenReturn(List.of());
        when(notificationSettingService.getSettings(USER_ID)).thenReturn(List.of());

        service.get(USER_ID);

        verify(availabilityService).getMyAvailabilities(USER_ID);
        verify(preferencesService).get(USER_ID);
        verify(externalCalendarService).list(USER_ID);
        verify(notificationSettingService).getSettings(USER_ID);
    }

    @Test
    @DisplayName("한 조각이 실패하면 전체가 실패한다 — 반쯤 빈 설정 화면을 내지 않는다")
    void failsWholeWhenOnePartFails() {
        when(availabilityService.getMyAvailabilities(USER_ID)).thenReturn(mock(AvailabilityView.class));
        when(preferencesService.get(USER_ID)).thenThrow(new IllegalStateException("preferences 조회 실패"));

        assertThatThrownBy(() -> service.get(USER_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
