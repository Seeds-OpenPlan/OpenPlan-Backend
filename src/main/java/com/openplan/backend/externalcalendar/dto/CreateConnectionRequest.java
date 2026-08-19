package com.openplan.backend.externalcalendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 연동 추가 요청 (ONB-07 · FIX-14) — openapi {@code createConnection}.
 *
 * <p>{@code provider}는 계약이 {@code enum: [GOOGLE]}로 못박고 있다. 문자열로 받되 서비스가
 * 지원 여부를 판정한다 — 알 수 없는 값에 500 이 아니라 계약된 오류를 돌려주기 위해서다.
 *
 * <p>{@code redirectUri}를 요청에 싣는 것은 형식이 아니라 <b>검증 대상</b>이다. 제공자가 인가 때 쓴 값과
 * 대조하며 한 글자만 달라도 교환을 거부한다.
 *
 * <p>{@code state}는 인가를 시작한 것이 이 세션임을 증명한다 — 없으면 공격자가 자기 캘린더의 인가 코드를
 * 남의 세션에 붙여 <b>피해자 계정에 자기 캘린더를 연결</b>시킬 수 있다(CSRF).
 */
public record CreateConnectionRequest(
        @NotNull String provider,
        @NotBlank String authCode,
        @NotBlank String redirectUri,
        @NotBlank String state) {
}
