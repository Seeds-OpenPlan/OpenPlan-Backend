package com.openplan.backend.executionlog.dto;

import com.openplan.backend.executionlog.domain.ExecutionLog;

import java.time.Instant;
import java.util.UUID;

/**
 * 수행 이력 응답 — 정본 openapi.yaml {@code ExecutionLog} shape 그대로.
 *
 * <p>명세에 없는 필드는 싣지 않는다({@code userId}·{@code planBlockId}·{@code createdAt} 미포함).
 */
public record ExecutionLogResponse(
        UUID executionLogId,
        UUID taskId,
        Instant startedAt,
        Instant endedAt,
        Integer actualMinutes,
        String result,
        String memo) {

    public static ExecutionLogResponse from(ExecutionLog log) {
        return new ExecutionLogResponse(
                log.getId(),
                log.getTaskId(),
                log.getStartedAt(),
                log.getEndedAt(),
                log.getActualMinutes(),
                log.getResult().name(),
                log.getMemo());
    }
}
