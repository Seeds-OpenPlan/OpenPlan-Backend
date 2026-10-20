package com.openplan.backend.weeklyplan.dto;

/**
 * 계획 설명 응답 (정본 openapi.yaml {@code PlanExplanation}). {@code model}은 관측용으로 그대로 노출한다 —
 * 다른 AI 접합의 {@code meta}(사용자 비노출)와 다르게 여기선 숨기지 않는 이유는, 이 응답 자체가 생성문이라
 * 사용자가 "무엇이 이 글을 썼는지" 알 근거가 되고, FE가 폴백 없음을 전제로 디버깅할 유일한 단서이기 때문이다.
 */
public record PlanExplanationResponse(String explanation, String model) {
}
