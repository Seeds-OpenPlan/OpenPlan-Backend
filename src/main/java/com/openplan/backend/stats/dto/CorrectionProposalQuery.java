package com.openplan.backend.stats.dto;

import java.util.UUID;

/**
 * 보정 제안 조회 쿼리 (SS-11 / RB-STAT-02). 정본상 <b>세 필드 모두 선택</b>이다.
 *
 * <p><b>제약 애너테이션을 두지 않는다</b>: {@code @NotNull}류를 붙이면 미제공이 400 E-COM-001이 되는데,
 * 정본은 미제공을 오류가 아니라 <b>의미 있는 입력</b>으로 규정한다 — categoryId·projectId 미제공은
 * "전체 이력 스코프", estimatedMinutes 미제공은 "제안 불가(data 생략)"다. 값 규칙(5분 단위) 위반만
 * 422로 판정하며 그것은 {@link com.openplan.backend.stats.service.StatsQueryValidator} 소관이다.
 * 필수 필드에만 {@code @NotBlank}를 붙이는 {@code DeviationsQuery} 관례의 역적용이다.
 */
public class CorrectionProposalQuery {

    private UUID categoryId;
    private UUID projectId;
    private Integer estimatedMinutes;

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public Integer getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public void setEstimatedMinutes(Integer estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
    }
}
