package com.openplan.backend.push.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 구독 등록 요청 — 브라우저 Push API {@code PushSubscription}을 그대로 받는다(ADR-0015 결정 ④).
 * {@code platform}은 enum이 아니라 문자열로 받는다 — 서비스 계층이 파싱해 {@code ANDROID_APP}이 아니면
 * 422로 거절한다({@code NotificationSettingService.parseType}과 같은 패턴 — 컨트롤러 바인딩 실패(400)가
 * 아니라 "알 수 없는 값"이라는 의미를 살린 422여야 하기 때문).
 */
public record PushSubscribeRequest(@NotBlank String endpoint,
                                   @NotNull @Valid Keys keys,
                                   @NotBlank String platform) {

    public record Keys(@NotBlank String p256dh, @NotBlank String auth) {
    }
}
