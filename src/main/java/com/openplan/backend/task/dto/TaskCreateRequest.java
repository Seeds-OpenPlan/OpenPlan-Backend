package com.openplan.backend.task.dto;

import jakarta.validation.constraints.Null;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 태스크 생성 요청 (PROJ-17 / EP-2).
 *
 * <p>title은 <b>@NotBlank를 두지 않는다</b> — null/공백/길이 규칙은 서비스({@code TaskValidator})가
 * 422 E-COM-009로 판정한다(Bean Validation은 400을 내므로 AC-C-3의 422와 충돌). 그 대가로 title 키
 * 부재 시 record가 null로 역직렬화되므로 Validator 첫 줄 null 가드가 필수다(C2).
 *
 * <p>status는 <b>서버가 규정</b>한다(생성 시 UNASSIGNED) — 요청에 포함되면 {@code @Null} 위반으로
 * 400 E-COM-001(침묵 무시 금지 — AC-C-8). {@code String} 타입이라 임의 문자열이 실려도 Jackson
 * 역직렬화 실패 없이 400으로 착지한다([M1] — enum 타이핑 시 글로벌 핸들러 갭과 충돌해 500으로 샘).
 */
public record TaskCreateRequest(
        String title,
        String memo,
        Integer estimatedMinutes,
        Integer priority,
        LocalDate dueDate,
        UUID categoryId,
        @Null(message = "status는 지정할 수 없습니다.") String status) {
}
