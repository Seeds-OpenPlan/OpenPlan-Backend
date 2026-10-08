package com.openplan.backend.push.service;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.push.domain.PushSetting;
import com.openplan.backend.push.dto.PushSettingResponse;
import com.openplan.backend.push.dto.SavePushSettingsRequest;
import com.openplan.backend.push.repository.PushSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 푸시 설정 (ADR-0015 결정 ⑥). 기본 전부 꺼짐이라 알림 설정(NOTI-01)과 달리 <b>지연 시드가 없다</b> —
 * 행 부재를 바로 "꺼짐"으로 읽으면 되므로, 조회가 쓰기를 유발하지 않는다(읽기 트랜잭션이 더 가볍다).
 */
@Service
public class PushSettingService {

    private final PushSettingRepository repository;
    private final UserClock clock;

    public PushSettingService(PushSettingRepository repository, UserClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** GET /users/me/push-settings — 행이 없으면 전부 꺼짐. */
    @Transactional(readOnly = true)
    public PushSettingResponse getSettings(UUID userId) {
        return repository.findByUserId(userId)
                .map(PushSettingResponse::from)
                .orElse(PushSettingResponse.ALL_OFF);
    }

    /** PUT /users/me/push-settings — upsert(행이 없으면 생성, 있으면 갈아끼운다). */
    @Transactional
    public PushSettingResponse saveSettings(UUID userId, SavePushSettingsRequest request) {
        Instant now = clock.now();
        PushSetting setting = repository.findByUserId(userId).orElse(null);
        if (setting == null) {
            setting = PushSetting.create(userId, request.taskEnabled(), request.fixedScheduleEnabled(),
                    request.scheduleEnabled(), now);
        } else {
            setting.change(request.taskEnabled(), request.fixedScheduleEnabled(), request.scheduleEnabled(), now);
        }
        return PushSettingResponse.from(repository.save(setting));
    }
}
