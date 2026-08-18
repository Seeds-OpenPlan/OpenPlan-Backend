package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.rule.model.ValidationIssue;

import java.util.UUID;

/**
 * 검증 이슈 응답 (정본 openapi.yaml {@code ValidationIssue} 1:1). 엔진 판정({@link ValidationIssue})을 그대로 노출한다.
 *
 * <p>{@code validationIssueId}는 영속 판정만 값(dry-run은 null). {@code resolutionStatus}는 영속 시 OPEN.
 * 규칙별 축 필드만 채워진다(쌍 규칙=planBlockId·counterpartId / 요일 규칙=weekday) — 엔진이 정한 그대로 전달.
 */
public record ValidationIssueResponse(
        UUID validationIssueId,
        String ruleId,
        String severity,
        UUID planBlockId,
        UUID counterpartId,
        UUID taskId,
        String weekday,
        String reason,
        String resolutionStatus) {

    /** 엔진 판정 → 응답. persistedId=null이면 dry-run(무영속). */
    public static ValidationIssueResponse of(ValidationIssue i, UUID persistedId) {
        return new ValidationIssueResponse(
                persistedId,
                i.ruleId().name(),
                i.severity().name(),
                i.planBlockId(),
                i.counterpartId(),
                i.taskId(),
                i.weekday() == null ? null : i.weekday().name(),
                i.reason(),
                "OPEN");
    }
}
