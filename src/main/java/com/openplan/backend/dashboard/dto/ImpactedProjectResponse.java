package com.openplan.backend.dashboard.dto;

import java.util.List;
import java.util.UUID;

/** DASH-06 영향 프로젝트 1행 — badges가 1개 이상 있는 프로젝트만 포함한다(빈 배지면 "영향 없음"과 동치). */
public record ImpactedProjectResponse(UUID projectId, String name, List<String> impactBadges) {
}
