package com.openplan.backend.push.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.push.domain.PushPlatform;
import com.openplan.backend.push.domain.PushSubscription;
import com.openplan.backend.push.dto.PushSubscribeRequest;
import com.openplan.backend.push.repository.PushSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 구독 등록·해지 (ADR-0015 결정 ④). endpoint UNIQUE가 "기기당 1행"의 근거이자 재할당의 키다.
 */
@Service
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final UserClock clock;
    private final ErrorMessages errorMessages;

    public PushSubscriptionService(PushSubscriptionRepository repository, UserClock clock,
                                   ErrorMessages errorMessages) {
        this.repository = repository;
        this.clock = clock;
        this.errorMessages = errorMessages;
    }

    /**
     * POST /users/me/push-subscriptions — endpoint로 upsert. 이미 있는 endpoint면 소유자·키를
     * 갈아끼운다(기기가 다른 계정으로 로그인했거나, 브라우저가 키를 재발급한 경우 — 둘 다 "이 기기의
     * 지금 진짜 구독"이 새 값이라는 뜻이다).
     */
    @Transactional
    public void subscribe(UUID userId, PushSubscribeRequest request) {
        PushPlatform platform = parsePlatform(request.platform());
        PushSubscription existing = repository.findByEndpoint(request.endpoint()).orElse(null);
        if (existing != null) {
            existing.reassignTo(userId, request.keys().p256dh(), request.keys().auth(), platform);
            repository.save(existing);
            return;
        }
        repository.save(PushSubscription.create(userId, request.endpoint(), request.keys().p256dh(),
                request.keys().auth(), platform, clock.now()));
    }

    /** DELETE /users/me/push-subscriptions — 소유자 스코프. 부재·타인 소유 모두 조용히 204(멱등). */
    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        repository.deleteByUserIdAndEndpoint(userId, endpoint);
    }

    /**
     * ADR-0015 결정 ④ — 안드로이드 앱만. 다른 값은 422 E-COM-009 — details.fields 모양은
     * {@code PlanBlockService.invalidField}와 같은 규약({@code validation.{field}.{rule}} 카탈로그 키).
     */
    private PushPlatform parsePlatform(String raw) {
        try {
            return PushPlatform.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            String message = errorMessages.resolve("validation.platform.invalid");
            throw new OpenPlanException(ErrorCode.E_COM_009,
                    Map.of("fields", List.of(Map.of("field", "platform", "rule", "invalid", "message", message))));
        }
    }
}
