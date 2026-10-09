package com.openplan.backend.push.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 푸시 설정 저장 요청 (PUT) — GET 응답과 같은 모양이다(ADR-0015 결정 ⑥). 3종 전부 보내야 한다(부분 저장
 * 불허 — 알림 설정(NOTI-01)과 달리 토글이 3개뿐이라 화면이 항상 전체를 한 번에 보여주고 저장한다).
 */
public record SavePushSettingsRequest(@NotNull Boolean taskEnabled,
                                      @NotNull Boolean fixedScheduleEnabled,
                                      @NotNull Boolean scheduleEnabled) {
}
