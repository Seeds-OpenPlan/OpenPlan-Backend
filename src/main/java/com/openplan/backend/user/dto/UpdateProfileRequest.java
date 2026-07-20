package com.openplan.backend.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 프로필 수정 요청(PATCH /users/me/profile). 부분 수정 — 모든 필드가 선택이며,
 * {@code null}(미제공) 필드는 변경하지 않는다.
 *
 * <p>검증은 위반 시 {@code MethodArgumentNotValidException} → E-COM-001(+fields)로 매핑된다
 * (GlobalExceptionHandler). 시맨틱 검증(실재 시간대 여부)은 서비스에서 E-COM-009로 처리한다.
 *
 * <p>주의: name 한도는 DB {@code user_profiles.name VARCHAR(50)}에 맞춰 50이다. openapi 초안이
 * maxLength 100으로 적혀 있으나, 50 초과를 허용하면 DB 삽입 시 500이 되므로 DB 제약(50)을 정본으로 삼는다
 * (계약 diff는 be1-notes에 기록 — openapi 정정 대상).
 *
 * <p>weekStartDay는 String으로 받아 {@link Pattern}으로 검증한다 — enum 직접 역직렬화 시 잘못된 값이
 * {@code HttpMessageNotReadableException}(→ E-COM-005/500)이 되는 것을 피하고 E-COM-001로 안내하기 위함.
 */
public record UpdateProfileRequest(

        @Size(min = 1, max = 50, message = "name must be 1..50 characters")
        String name,

        @Size(max = 100, message = "purpose must be at most 100 characters")
        String purpose,

        @Size(min = 1, max = 50, message = "timezone must be 1..50 characters")
        String timezone,

        @Pattern(regexp = "MON|TUE|WED|THU|FRI|SAT|SUN", message = "weekStartDay must be one of MON..SUN")
        String weekStartDay
) {
}
