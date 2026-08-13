package com.openplan.backend.executionlog.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 수행 이력 기록 요청 (PLAN-15 / 정본 openapi.yaml {@code recordExecution} requestBody).
 *
 * <p>{@code result}는 <b>String으로 받는다</b> — enum 바인딩 시 미정의값이 파싱 단계에서 500으로 새는 것을 피하고,
 * 값 규칙 위반을 서비스가 422 E-COM-009로 판정하기 위함(블록 {@code blockType}·고정 일정 {@code weekday}와 동일 컨벤션).
 * null/범위 규칙은 Bean Validation을 두지 않고 {@code ExecutionLogValidator}가 422로 판정한다(오류 형태 단일화).
 *
 * <p>{@code planBlockId}는 선택 — 어느 계획 블록을 수행한 것인지 잇는다. 담기면 소유자 확인을 거친다.
 */
public record ExecutionLogCreateRequest(
        UUID planBlockId,
        Instant startedAt,
        Instant endedAt,
        Integer actualMinutes,
        String result,
        String memo) {
}
