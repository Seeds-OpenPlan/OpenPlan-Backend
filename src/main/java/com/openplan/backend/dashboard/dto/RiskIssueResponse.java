package com.openplan.backend.dashboard.dto;

/** DASH-03/04 위험 목록 1행. count=0인 유형은 목록에서 제외한다(전 유형 0건이면 빈 배열). */
public record RiskIssueResponse(String riskType, int count, String description, String routePath) {
}
