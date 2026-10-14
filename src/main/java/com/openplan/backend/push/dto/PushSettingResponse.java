package com.openplan.backend.push.dto;

import com.openplan.backend.push.domain.PushLeadTime;
import com.openplan.backend.push.domain.PushSetting;

/** 푸시 설정 응답 — 정본 {@code PushSettings} 스키마와 1:1. GET/PUT 공용(ADR-0015 결정 ⑥). */
public record PushSettingResponse(boolean taskEnabled, boolean fixedScheduleEnabled, boolean scheduleEnabled,
                                  int leadMinutes) {

    /** 행이 없는 사용자 — 전부 켜짐 · 10분 전(2026-10-11 사용자 결정). DDL default 와 같은 값이다. */
    public static final PushSettingResponse DEFAULT =
            new PushSettingResponse(true, true, true, PushLeadTime.DEFAULT_MINUTES);

    public static PushSettingResponse from(PushSetting s) {
        return new PushSettingResponse(s.isTaskEnabled(), s.isFixedScheduleEnabled(), s.isScheduleEnabled(),
                s.getLeadMinutes());
    }
}
