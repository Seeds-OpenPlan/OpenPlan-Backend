package com.openplan.backend.externalcalendar.dto;

import com.openplan.backend.fixedschedule.dto.FixedScheduleResponse;

/**
 * 반영 결과 (ONB-09) — openapi {@code applyExternalEvent} 201 응답의 정본 모양.
 *
 * <p>{@code data.fixedSchedule}은 생성된 {@code FixedSchedule} 스키마 전체(제목·요일·시각 등) 또는
 * {@code null}이어야 한다(openapi.yaml). 반영 직후 프론트가 별도 조회 없이 반영된 고정 일정을
 * 바로 그릴 수 있어야 하므로, ID 하나만 실어서는 계약을 만족하지 못한다.
 * {@code EXCLUDE}면 고정 일정이 생기지 않으므로 {@code fixedSchedule}이 null 이다.
 */
public record ApplyEventResponse(String applyStatus, FixedScheduleResponse fixedSchedule) {
}
