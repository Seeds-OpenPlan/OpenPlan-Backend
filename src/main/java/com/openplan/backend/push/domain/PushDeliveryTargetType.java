package com.openplan.backend.push.domain;

/**
 * 발송 대상 유형 (ADR-0015 결정 ②) — {@code push_deliveries.target_type} 및 DB CHECK와 1:1.
 * {@code target_id}의 의미는 유형마다 다르다: TASK/SCHEDULE은 {@code plan_block_id}, FIXED_SCHEDULE은
 * {@code fixed_schedule_id}(회차마다 {@code start_at}이 달라져 같은 FK라도 키가 겹치지 않는다).
 */
public enum PushDeliveryTargetType {
    TASK,
    SCHEDULE,
    FIXED_SCHEDULE
}
