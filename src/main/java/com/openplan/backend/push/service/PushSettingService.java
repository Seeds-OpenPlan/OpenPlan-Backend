package com.openplan.backend.push.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.push.domain.PushLeadTime;
import com.openplan.backend.push.domain.PushSetting;
import com.openplan.backend.push.dto.PushSettingResponse;
import com.openplan.backend.push.dto.SavePushSettingsRequest;
import com.openplan.backend.push.repository.PushSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 푸시 설정 (ADR-0015 결정 ⑥). 알림 설정(NOTI-01)과 달리 <b>지연 시드가 없다</b> — 행 부재를 바로
 * 기본값(전부 켜짐 · 10분 전)으로 읽으면 되므로, 조회가 쓰기를 유발하지 않는다(읽기 트랜잭션이 더 가볍다).
 */
@Service
public class PushSettingService {

    private final PushSettingRepository repository;
    private final UserClock clock;
    private final ErrorMessages errorMessages;

    public PushSettingService(PushSettingRepository repository, UserClock clock, ErrorMessages errorMessages) {
        this.repository = repository;
        this.clock = clock;
        this.errorMessages = errorMessages;
    }

    /** GET /users/me/push-settings — 행이 없으면 전부 켜짐 · 10분 전. */
    @Transactional(readOnly = true)
    public PushSettingResponse getSettings(UUID userId) {
        return repository.findByUserId(userId)
                .map(PushSettingResponse::from)
                .orElse(PushSettingResponse.DEFAULT);
    }

    /** PUT /users/me/push-settings — upsert(행이 없으면 생성, 있으면 갈아끼운다). */
    @Transactional
    public PushSettingResponse saveSettings(UUID userId, SavePushSettingsRequest request) {
        int leadMinutes = requireAllowedLeadMinutes(request.leadMinutes());
        Instant now = clock.now();
        PushSetting setting = repository.findByUserId(userId).orElse(null);
        if (setting == null) {
            setting = PushSetting.create(userId, request.taskEnabled(), request.fixedScheduleEnabled(),
                    request.scheduleEnabled(), leadMinutes, now);
        } else {
            setting.change(request.taskEnabled(), request.fixedScheduleEnabled(), request.scheduleEnabled(),
                    leadMinutes, now);
        }
        return PushSettingResponse.from(repository.save(setting));
    }

    /**
     * 보기 밖의 알림 시간은 422 E-COM-009 — DB CHECK 에 맡기면 500 이 된다. details.fields 모양은
     * {@code PushSubscriptionService.parsePlatform} 과 같은 규약.
     */
    private int requireAllowedLeadMinutes(Integer leadMinutes) {
        if (!PushLeadTime.isAllowed(leadMinutes)) {
            String message = errorMessages.resolve("validation.leadMinutes.invalid");
            throw new OpenPlanException(ErrorCode.E_COM_009,
                    Map.of("fields", List.of(Map.of("field", "leadMinutes", "rule", "invalid", "message", message))));
        }
        return leadMinutes;
    }
}
