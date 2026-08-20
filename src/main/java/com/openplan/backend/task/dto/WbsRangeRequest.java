package com.openplan.backend.task.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * WBS 기간 설정 요청 (ST-B2-05 / PUT {@code /tasks/{taskId}/wbs-range}). 정본 openapi.yaml
 * {@code required: [startDate, endDate]} 그대로 — 둘 다 구조적 필수라 누락(키 부재·null)은
 * {@code @NotNull} → 400 E-COM-001({@code WeeklyPlanCreateRequest.weekStartDate}와 동일 관례).
 *
 * <p>{@code endDate < startDate}(E-WBS-001) 판정은 두 값이 모두 있어야 가능한 관계 규칙이라
 * Bean Validation이 아니라 서비스 계층({@code TaskValidator.validateWbsRange})이 담당한다.
 */
public record WbsRangeRequest(
        @NotNull(message = "startDate는 필수입니다.") LocalDate startDate,
        @NotNull(message = "endDate는 필수입니다.") LocalDate endDate) {
}
