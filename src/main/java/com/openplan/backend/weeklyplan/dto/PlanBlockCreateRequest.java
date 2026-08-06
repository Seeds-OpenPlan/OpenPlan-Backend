package com.openplan.backend.weeklyplan.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 블록 배치 요청 (ST-B2-08 / PLAN-06·07). 이번 슬라이스는 TASK 블록만 — SCHEDULE은 후속.
 *
 * <p>blockType은 String으로 받아 서비스가 검증한다(enum 바인딩 시 미정의값이 파싱 단계 500으로 새는 것 회피).
 * taskId는 TASK 블록 필수(서비스 검증). startAt/endAt은 배치 시각(필수).
 */
public record PlanBlockCreateRequest(
        @NotNull(message = "blockType은 필수입니다.") String blockType,
        UUID taskId,
        @NotNull(message = "startAt은 필수입니다.") Instant startAt,
        @NotNull(message = "endAt은 필수입니다.") Instant endAt) {
}
