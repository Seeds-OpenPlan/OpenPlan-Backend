package com.openplan.backend.externalcalendar.dto;

import com.openplan.backend.common.Weekday;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/**
 * 반영 방식 적용 (ONB-09) — openapi {@code applyExternalEvent}.
 *
 * @param mode   {@code AS_IS} · {@code EDITED} · {@code EXCLUDE}
 * @param edited {@code mode=EDITED}일 때 고정 일정 필드 재정의. 그 외 모드에서는 무시한다.
 */
public record ApplyEventRequest(@NotNull String mode, Edited edited) {

    /** 부분 재정의 — null 인 필드는 원본 일정 값을 그대로 쓴다. */
    public record Edited(String title, Weekday weekday, LocalTime startTime, LocalTime endTime) {
    }
}
