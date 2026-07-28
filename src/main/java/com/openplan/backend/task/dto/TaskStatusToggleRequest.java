package com.openplan.backend.task.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 완료 토글 요청 (PLAN-13/14 / EP-5 · D-1a). {@code {completed, version}}(James 확정 바디 — ADR-B2-03-001).
 *
 * <p>{@code completed=true} = 완료로 표시(PLAN-13), {@code false} = 미완료로 되돌리기(PLAN-14). 착지 상태는
 * 서버가 결정한다(UNASSIGNED 직접 지정 불가 — 바디에 상태값 자체가 없음). completed·version 모두 필수
 * (@NotNull — 누락 시 400, AC-S-6).
 */
public record TaskStatusToggleRequest(
        @NotNull(message = "completed는 필수입니다.") Boolean completed,
        @NotNull(message = "version은 필수입니다.") Long version) {
}
