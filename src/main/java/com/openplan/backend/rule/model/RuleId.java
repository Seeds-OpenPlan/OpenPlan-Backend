package com.openplan.backend.rule.model;

/**
 * 검증 규칙 식별자 (rule-engine-contract §3.2).
 * V7_BUFFER_SHORTAGE 는 Q2 확정 전 엔진이 생성하지 않는다(값 창작 금지).
 */
public enum RuleId {
    V1_OVERLAP, V2_FIXED_CONFLICT, V3_CAPACITY_EXCEEDED,
    V4_OUT_OF_AVAILABILITY, V5_OUT_OF_WBS, V6_AFTER_DUE_DATE,
    V7_BUFFER_SHORTAGE
}
