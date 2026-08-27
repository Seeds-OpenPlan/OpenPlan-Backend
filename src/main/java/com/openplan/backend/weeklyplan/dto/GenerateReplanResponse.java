package com.openplan.backend.weeklyplan.dto;

import java.util.List;

/**
 * 재계획 대안 생성 응답 (SS-07~09 / 정본 openapi.yaml generateReplanOptions 201).
 * {@code baseline} = 현재 유지안(KEEP_CURRENT, id·저장 없음), {@code options} = 저장된 대안 3종.
 */
public record GenerateReplanResponse(
        ReplanOptionResponse baseline,
        List<ReplanOptionResponse> options) {
}
