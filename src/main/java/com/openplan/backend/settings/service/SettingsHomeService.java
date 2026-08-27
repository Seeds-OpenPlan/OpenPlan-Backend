package com.openplan.backend.settings.service;

import com.openplan.backend.availability.service.AvailabilityService;
import com.openplan.backend.externalcalendar.service.ExternalCalendarService;
import com.openplan.backend.notification.service.NotificationSettingService;
import com.openplan.backend.preference.service.PreferencesService;
import com.openplan.backend.settings.dto.SettingsHomeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 설정 홈 조립 (D-12 레버 5 · {@code GET /settings}).
 *
 * <p><b>조립만 한다.</b> 네 조각의 규칙은 각자의 서비스에 있고 여기서 다시 판단하지 않는다 —
 * 판단이 둘로 갈라지면 같은 값이 설정 홈과 개별 화면에서 다르게 보인다.
 *
 * <p>읽기 하나로 묶는 이유는 화면이 넷을 한 번에 그리기 때문이다. 네 번 왕복하면 부분적으로만
 * 채워진 화면이 사용자에게 보이고, 그 중간 상태는 "설정이 비어 있다"로 읽힌다.
 *
 * <p>🔴 <b>한 조각이 실패하면 전체가 실패한다.</b> 부분 성공을 허용하면 사용자는 비어 있는 절반을
 * "설정이 없음"으로 보고 다시 저장하게 되는데, 그 저장이 멀쩡하던 값을 덮는다. 조립 응답에서
 * 침묵하는 폴백은 데이터 손실 경로다(D-69).
 */
@Service
public class SettingsHomeService {

    private final AvailabilityService availabilityService;
    private final PreferencesService preferencesService;
    private final ExternalCalendarService externalCalendarService;
    private final NotificationSettingService notificationSettingService;

    public SettingsHomeService(AvailabilityService availabilityService,
                               PreferencesService preferencesService,
                               ExternalCalendarService externalCalendarService,
                               NotificationSettingService notificationSettingService) {
        this.availabilityService = availabilityService;
        this.preferencesService = preferencesService;
        this.externalCalendarService = externalCalendarService;
        this.notificationSettingService = notificationSettingService;
    }

    @Transactional(readOnly = true)
    public SettingsHomeResponse get(UUID userId) {
        return new SettingsHomeResponse(
                availabilityService.getMyAvailabilities(userId),
                preferencesService.get(userId),
                externalCalendarService.list(userId),
                notificationSettingService.getSettings(userId));
    }
}
