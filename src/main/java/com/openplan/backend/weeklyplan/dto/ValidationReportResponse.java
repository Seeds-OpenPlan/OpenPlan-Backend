package com.openplan.backend.weeklyplan.dto;

import java.time.Instant;
import java.util.List;

/**
 * 검증 결과 응답 (정본 openapi.yaml {@code ValidationReport}). 엔진 {@link com.openplan.backend.rule.model.ValidationReport}에
 * {@code dryRun} 플래그를 더해 노출한다.
 *
 * <p>{@code savable} = 차단(BLOCK) 0건(PLAN-28). <b>위반이 있어도 오류가 아니다</b> — 200 정상 응답.
 */
public record ValidationReportResponse(
        boolean dryRun,
        boolean savable,
        List<ValidationIssueResponse> issues,
        Instant evaluatedAt) {
}
