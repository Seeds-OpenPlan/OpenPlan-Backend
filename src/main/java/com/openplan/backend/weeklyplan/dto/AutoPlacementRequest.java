package com.openplan.backend.weeklyplan.dto;

import java.util.List;
import java.util.UUID;

/**
 * 자동 배치 요청 (RB-PLAN-01 / 정본 openapi.yaml {@code proposeAutoPlacement}).
 * {@code taskIds} 미지정(null·빈 목록)이면 미배치 전량이 대상. 본문 자체가 선택이다.
 */
public record AutoPlacementRequest(List<UUID> taskIds) {
}
