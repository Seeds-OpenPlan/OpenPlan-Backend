package com.openplan.backend.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 알림 설정 저장 요청 (NOTI-01).
 *
 * <p><b>부분 저장을 허용한다</b> — 보낸 유형만 반영하고 나머지는 건드리지 않는다. 5행 전량을 요구하면
 * 화면이 토글 하나를 바꿀 때도 전체를 재전송해야 하고, 그 사이 유형이 늘면 옛 화면이 신규 유형을 지운다.
 */
public record SaveNotificationSettingsRequest(@NotEmpty @Valid List<Item> settings) {

    public record Item(@NotNull String notificationType, @NotNull Boolean isEnabled) {
    }
}
