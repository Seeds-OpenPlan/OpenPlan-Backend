package com.openplan.backend.stats.dto;

import java.util.UUID;

/**
 * 그룹(프로젝트/카테고리) 1행. {@code groupId=null}은 카테고리 미지정 그룹 — {@code groupName="없음"}으로
 * 고정 노출한다(openapi 설명 그대로, service-stories.md SS-10 AC②).
 */
public record DeviationRowResponse(
        UUID groupId,
        String groupName,
        int estimatedMinutes,
        int actualMinutes,
        int deviationMinutes,
        Double deviationRate) {
}
