package com.openplan.backend.project.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;

import java.time.LocalDate;

/**
 * 프로젝트 편집 요청 (PROJ-06 / EP-4) — PUT 전체 교체. 편집 가능 4필드(name·description·dueDate·priority)를
 * 모두 실어 보낸다(부분 갱신 아님 — 생략된 필드는 null로 교체됨).
 *
 * <p>{@code version}은 낙관락 입력이라 필수(누락 → 400). name은 생성과 동일하게 서비스가 422로 검증한다
 * (@NotBlank 두지 않음 — C2 null 가드가 서비스에 있음). status·closedAt은 편집으로 못 바꾼다(@Null → 400).
 */
public record ProjectUpdateRequest(
        String name,
        String description,
        LocalDate dueDate,
        Integer priority,
        @NotNull(message = "version은 필수입니다.") Long version,
        @Null(message = "status는 편집으로 변경할 수 없습니다.") String status,
        @Null(message = "closedAt는 지정할 수 없습니다.") String closedAt) {
}
