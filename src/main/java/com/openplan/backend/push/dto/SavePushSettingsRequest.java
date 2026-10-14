package com.openplan.backend.push.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 푸시 설정 저장 요청 (PUT) — GET 응답과 같은 모양이다(ADR-0015 결정 ⑥). 토글 3종과 알림 시간을 전부
 * 보내야 한다(부분 저장 불허 — 알림 설정(NOTI-01)과 달리 항목이 넷뿐이라 화면이 항상 전체를 한 번에 보여주고
 * 저장한다). 알림 시간은 보기 밖이면 422 — 서비스가 판정한다({@code PushLeadTime}).
 */
public record SavePushSettingsRequest(@NotNull Boolean taskEnabled,
                                      @NotNull Boolean fixedScheduleEnabled,
                                      @NotNull Boolean scheduleEnabled,
                                      @NotNull Integer leadMinutes) {
}
