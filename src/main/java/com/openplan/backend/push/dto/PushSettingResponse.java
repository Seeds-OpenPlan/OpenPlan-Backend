package com.openplan.backend.push.dto;

import com.openplan.backend.push.domain.PushSetting;

/** 푸시 설정 응답 — 정본 {@code PushSettings} 스키마와 1:1. GET/PUT 공용(ADR-0015 결정 ⑥). */
public record PushSettingResponse(boolean taskEnabled, boolean fixedScheduleEnabled, boolean scheduleEnabled) {

    /** 행이 없는 사용자 — 전부 꺼짐(결정 ⑥: "행이 없으면 전부 꺼짐으로 읽는다"). */
    public static final PushSettingResponse ALL_OFF = new PushSettingResponse(false, false, false);

    public static PushSettingResponse from(PushSetting s) {
        return new PushSettingResponse(s.isTaskEnabled(), s.isFixedScheduleEnabled(), s.isScheduleEnabled());
    }
}
