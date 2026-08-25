package com.openplan.backend.notification.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.notification.domain.NotificationSetting;
import com.openplan.backend.notification.domain.NotificationType;
import com.openplan.backend.notification.dto.NotificationSettingResponse;
import com.openplan.backend.notification.dto.SaveNotificationSettingsRequest;
import com.openplan.backend.notification.repository.NotificationSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 알림 설정 (NOTI-01 · ST-B1-12).
 *
 * <p><b>지연 시드가 이 클래스의 핵심이다.</b> {@code notifications.notification_setting_id}가
 * NOT NULL FK 라서, 알림을 만들려면 설정 5행이 먼저 있어야 한다. 가입 시점에 심지 않고
 * <b>처음 필요해질 때</b> 만든다(ADR-0014) — 가입 경로에 이 도메인을 끼워 넣지 않으려는 것이다.
 * 조회·저장·판정이 모두 같은 시드를 공유한다.
 */
@Service
public class NotificationSettingService {

    private final NotificationSettingRepository repository;
    private final UserClock clock;

    public NotificationSettingService(NotificationSettingRepository repository, UserClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 설정 5행을 보장하고 유형별로 돌려준다 — 없으면 기본 켜짐으로 만든다(DDL default 와 같은 값).
     *
     * <p>동시 요청 경합은 {@code ux_noti_setting}(user_id, notification_type)이 백스톱이다.
     * 여기서 예외를 잡아 이어가면 트랜잭션이 rollback-only 로 표시돼 되레 500 이 되므로
     * <b>잡지 않는다</b> — 재시도는 클라이언트 몫이고, 알림 조회는 재시도가 안전한 읽기다.
     */
    @Transactional
    public Map<NotificationType, NotificationSetting> ensureSettings(UUID userId) {
        Map<NotificationType, NotificationSetting> byType = new EnumMap<>(NotificationType.class);
        for (NotificationSetting s : repository.findByUserId(userId)) {
            byType.put(s.getNotificationType(), s);
        }
        if (byType.size() == NotificationType.values().length) {
            return byType;
        }
        Instant now = clock.now();
        for (NotificationType type : NotificationType.values()) {
            byType.computeIfAbsent(type, t -> repository.save(new NotificationSetting(userId, t, now)));
        }
        return byType;
    }

    /** GET /users/me/notification-settings — 선언 순서(유형 enum 순)로 5건 고정 반환. */
    @Transactional
    public List<NotificationSettingResponse> getSettings(UUID userId) {
        Map<NotificationType, NotificationSetting> byType = ensureSettings(userId);
        return java.util.Arrays.stream(NotificationType.values())
                .map(byType::get)
                .map(NotificationSettingResponse::from)
                .toList();
    }

    /**
     * PUT /users/me/notification-settings — 보낸 유형만 반영한다(부분 저장).
     *
     * <p>알 수 없는 유형 문자열은 422 E-COM-009 로 거절한다. 조용히 무시하면 화면은 저장됐다고
     * 표시하는데 서버는 아무것도 안 바꾼 상태가 되어, 사용자가 껐다고 믿는 알림이 계속 온다.
     */
    @Transactional
    public List<NotificationSettingResponse> saveSettings(UUID userId, SaveNotificationSettingsRequest request) {
        Map<NotificationType, NotificationSetting> byType = ensureSettings(userId);
        Instant now = clock.now();
        for (SaveNotificationSettingsRequest.Item item : request.settings()) {
            NotificationType type = parseType(item.notificationType());
            byType.get(type).changeEnabled(item.isEnabled(), now);
        }
        return java.util.Arrays.stream(NotificationType.values())
                .map(byType::get)
                .map(NotificationSettingResponse::from)
                .toList();
    }

    private NotificationType parseType(String raw) {
        try {
            return NotificationType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new OpenPlanException(ErrorCode.E_COM_009,
                    Map.of("field", "settings.notificationType", "rule", "invalid"));
        }
    }
}
