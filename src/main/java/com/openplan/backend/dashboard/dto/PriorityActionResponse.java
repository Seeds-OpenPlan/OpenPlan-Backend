package com.openplan.backend.dashboard.dto;

/** RB-DASH-01 최상위 1행동. 후보 0건이면 이 레코드 자체가 아니라 {@code null}로 응답(긍정 상태 카드, FE 소관). */
public record PriorityActionResponse(String actionType, String reason, String routePath) {
}
