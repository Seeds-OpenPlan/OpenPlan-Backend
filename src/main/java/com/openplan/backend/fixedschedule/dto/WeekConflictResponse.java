package com.openplan.backend.fixedschedule.dto;

import com.openplan.backend.weeklyplan.dto.ValidationIssueResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 주차별 충돌 응답 항목 (FIX-07 / 정본 {@code data[]} — {weekStartDate, issues}).
 *
 * <p>{@code issues}는 검증 엔진 판정을 그대로 노출하므로 weeklyplan 도메인의
 * {@link ValidationIssueResponse}를 재사용한다 — 정본이 같은 {@code ValidationIssue} 스키마를
 * 가리키고 있어 shape을 복제하면 두 곳이 갈라진다. 무영속이라 {@code validationIssueId}는 항상 null.
 */
public record WeekConflictResponse(LocalDate weekStartDate, List<ValidationIssueResponse> issues) {
}
