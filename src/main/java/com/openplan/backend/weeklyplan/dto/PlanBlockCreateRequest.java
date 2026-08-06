package com.openplan.backend.weeklyplan.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 블록 배치 요청 (ST-B2-08 / PLAN-06·07·08). blockType에 따라 사용하는 필드가 다르다.
 *
 * <p><b>TASK</b>(PLAN-06/07): 기존 태스크 배치 → {@code taskId} 사용.
 * <b>SCHEDULE</b>(PLAN-08): 일정을 새로 만들며 배치 → {@code title·estimatedMinutes·priority·memo} 사용.
 *
 * <p>blockType은 String으로 받아 서비스가 검증한다(enum 바인딩 시 미정의값이 파싱 단계 500으로 새는 것 회피).
 * 필드별 필수·규칙(taskId/title 등)은 서비스가 blockType에 맞춰 판정한다. startAt/endAt은 공통 필수.
 */
public record PlanBlockCreateRequest(
        @NotNull(message = "blockType은 필수입니다.") String blockType,

        // TASK 전용
        UUID taskId,

        // SCHEDULE 전용 (일정 생성 입력)
        String title,
        Integer estimatedMinutes,
        Integer priority,
        String memo,

        @NotNull(message = "startAt은 필수입니다.") Instant startAt,
        @NotNull(message = "endAt은 필수입니다.") Instant endAt) {
}
