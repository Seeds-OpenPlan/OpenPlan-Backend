package com.openplan.backend.project.dto;

import jakarta.validation.constraints.Null;

import java.time.LocalDate;

/**
 * 프로젝트 생성 요청 (PROJ-02 / EP-2).
 *
 * <p>name은 <b>@NotBlank를 두지 않는다</b> — null/공백/길이 규칙은 서비스({@code ProjectValidator})가
 * 422 E-COM-009로 판정한다(Bean Validation은 400을 내므로 AC-02-3의 422와 충돌). 그 대가로 name 키
 * 부재 시 record가 null로 역직렬화되므로 Validator 첫 줄 null 가드가 필수다(C2).
 *
 * <p>status·closedAt은 <b>서버가 규정</b>한다(status=IN_PROGRESS, closedAt=null) — 요청에 포함되면
 * {@code @Null} 위반으로 400 E-COM-001(침묵 무시 금지). 문자열 타입이라 값이 실려도 파싱 문제 없이 400.
 */
public record ProjectCreateRequest(
        String name,
        String description,
        LocalDate dueDate,
        Integer priority,
        @Null(message = "status는 지정할 수 없습니다.") String status,
        @Null(message = "closedAt는 지정할 수 없습니다.") String closedAt) {
}
