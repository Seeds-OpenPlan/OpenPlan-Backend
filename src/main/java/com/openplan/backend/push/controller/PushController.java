package com.openplan.backend.push.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.push.dto.PushSettingResponse;
import com.openplan.backend.push.dto.PushSubscribeRequest;
import com.openplan.backend.push.dto.PushUnsubscribeRequest;
import com.openplan.backend.push.dto.SavePushSettingsRequest;
import com.openplan.backend.push.dto.VapidPublicKeyResponse;
import com.openplan.backend.push.infra.VapidProperties;
import com.openplan.backend.push.service.PushSettingService;
import com.openplan.backend.push.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 푸시 설정·구독·공개키 (ADR-0015). 알림(notification) 도메인과 별개 — 대상(토글 3종)과 메커니즘
 * (발송기 vs 지연 생성)이 다르다. {@code /users/me/...} 하위 2 EP는 알림 설정 컨트롤러와 같은 이유로
 * 소유 도메인(push) 기준으로 여기 둔다.
 */
@RestController
@Tag(name = "push", description = "시작 10분 전 푸시 알림 (ADR-0015)")
public class PushController {

    private final PushSettingService settingService;
    private final PushSubscriptionService subscriptionService;
    private final VapidProperties vapidProperties;

    public PushController(PushSettingService settingService, PushSubscriptionService subscriptionService,
                          VapidProperties vapidProperties) {
        this.settingService = settingService;
        this.subscriptionService = subscriptionService;
        this.vapidProperties = vapidProperties;
    }

    @GetMapping("/users/me/push-settings")
    @Operation(summary = "푸시 설정 조회 — 3토글, 행이 없으면 전부 꺼짐")
    public ApiResponse<PushSettingResponse> getSettings(@CurrentUser UUID userId) {
        return ApiResponse.ok(settingService.getSettings(userId));
    }

    @PutMapping("/users/me/push-settings")
    @Operation(summary = "푸시 설정 저장 — 3토글 전부 전송(부분 저장 없음)")
    public ApiResponse<PushSettingResponse> saveSettings(@CurrentUser UUID userId,
                                                         @Valid @RequestBody SavePushSettingsRequest request) {
        return ApiResponse.ok(settingService.saveSettings(userId, request));
    }

    @GetMapping("/push/vapid-public-key")
    @Operation(summary = "VAPID 공개키 — 미설정이면 null(FE가 섹션을 숨긴다)")
    public ApiResponse<VapidPublicKeyResponse> getVapidPublicKey() {
        String publicKey = vapidProperties.configuredOrEmpty().map(VapidProperties::publicKey).orElse(null);
        return ApiResponse.ok(new VapidPublicKeyResponse(publicKey));
    }

    @PostMapping("/users/me/push-subscriptions")
    @Operation(summary = "구독 등록(upsert by endpoint) — platform!=ANDROID_APP 은 422")
    public ApiResponse<Void> subscribe(@CurrentUser UUID userId, @Valid @RequestBody PushSubscribeRequest request) {
        subscriptionService.subscribe(userId, request);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/users/me/push-subscriptions")
    @Operation(summary = "구독 해지 — 본인 소유만(멱등)",
            description = "부재·타인 소유여도 204(존재 은닉 — 다른 삭제 EP와 같은 규약). 봉투 없음.")
    public ResponseEntity<Void> unsubscribe(@CurrentUser UUID userId,
                                            @Valid @RequestBody PushUnsubscribeRequest request) {
        subscriptionService.unsubscribe(userId, request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
