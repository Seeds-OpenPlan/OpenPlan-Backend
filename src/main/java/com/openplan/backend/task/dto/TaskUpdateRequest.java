package com.openplan.backend.task.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 태스크 편집 요청 (PROJ-18=PLAN-10 / EP-4). <b>전체 폼 교체 의미론</b>(James 확정) — 6필드를 전부 제출하며
 * null 가능 필드의 null은 "비움/해제"를 뜻한다(tri-state 회피, record DTO 유지). {@code categoryId=null} = 해제(AC-E-1).
 *
 * <p>title은 <b>@NotBlank를 두지 않는다</b> — 규칙은 서비스({@code TaskValidator})가 422로 판정(생성과 동일 코드, AC-E-3).
 * dueDate는 검증하지 않는다 — 과거 허용(D-11).
 *
 * <p>{@code version}은 낙관락 입력이라 <b>필수</b>(@NotNull — 누락 시 400, AC-E-4). status는 편집으로 못 바꾼다 —
 * 포함되면 {@code @Null} 위반 400(D-3 · AC-E-2, 상태는 /status 전용). String 타입이라 값이 실려도 파싱 문제 없이 400([M1]).
 */
public record TaskUpdateRequest(
        String title,
        String memo,
        Integer estimatedMinutes,
        Integer priority,
        LocalDate dueDate,
        UUID categoryId,
        @NotNull(message = "version은 필수입니다.") Long version,
        @Null(message = "status는 지정할 수 없습니다.") String status) {
}
