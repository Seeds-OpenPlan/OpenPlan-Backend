package com.openplan.backend.push.dto;

import jakarta.validation.constraints.NotBlank;

/** 구독 해지 요청(DELETE 바디) — endpoint로만 식별한다. */
public record PushUnsubscribeRequest(@NotBlank String endpoint) {
}
