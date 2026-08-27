package com.openplan.backend.externalcalendar.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 가져올 캘린더 선택 저장 (ONB-08 · FIX-15) — openapi {@code saveCalendarSelections}.
 *
 * <p><b>전체 교체다.</b> 목록에서 빠진 캘린더는 선택 해제가 아니라 삭제된다. 빈 배열은 유효하며
 * "아무것도 가져오지 않음"을 뜻한다 — 그래서 {@code @NotEmpty}가 아니라 {@code @NotNull}이다.
 */
public record SaveSelectionsRequest(@NotNull @Valid List<Selection> selections) {

    public record Selection(@NotBlank String externalCalendarId, @NotBlank String name) {
    }
}
