package com.openplan.backend.externalcalendar.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 연동 활성/비활성 (FIX-16) — openapi {@code updateConnection}. 값은 {@code CONNECTED}/{@code DISABLED}
 * 이며 저장 어휘(ACTIVE/INACTIVE)로의 변환은 {@code ConnectionStatus}가 전담한다.
 */
public record UpdateConnectionRequest(@NotBlank String status) {
}
