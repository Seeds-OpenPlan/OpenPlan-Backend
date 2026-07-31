package com.openplan.backend.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 문의 등록 요청(POST /support/tickets).
 *
 * <p>category는 String+@Pattern으로 받는다 — enum 직접 역직렬화 시 잘못된 값이 500이 되는 것을 피하고
 * E-COM-001로 안내하기 위함. title 한도는 DB {@code support_tickets.title VARCHAR(200)}에 맞춘 200이다
 * (openapi 초안 255 → DB 정본 200, be1-notes에 계약 diff 기록).
 */
public record CreateTicketRequest(

        @NotBlank(message = "category is required")
        @Pattern(regexp = "BUG|ACCOUNT|PLAN|ETC", message = "category must be one of BUG, ACCOUNT, PLAN, ETC")
        String category,

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        @NotBlank(message = "content is required")
        String content
) {
}
