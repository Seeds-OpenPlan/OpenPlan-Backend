package com.openplan.backend.structuring.dto;

import com.openplan.backend.structuring.domain.StructureWarningAction;
import com.openplan.backend.structuring.domain.StructureWarningType;

/**
 * 구조 부족 경고 항목 (SS-04 / RB-PROJ-02 — 정본 openapi.yaml {@code structure-warnings} data 항목).
 *
 * <p>{@code reason}은 <b>사실 서술</b>이다(P4) — AI·자동 분석 같은 표현을 쓰지 않고, 판정에 쓴 숫자와
 * 기준을 그대로 밝힌다. 문구는 {@code StructureWarningPolicy}가 문자열 결합으로만 만든다
 * (포매터·Locale에 기대면 환경에 따라 결과가 달라져 골든이 깨진다 — P1 결정성).
 */
public record StructureWarningResponse(
        StructureWarningType warningType,
        String reason,
        StructureWarningAction action) {
}
