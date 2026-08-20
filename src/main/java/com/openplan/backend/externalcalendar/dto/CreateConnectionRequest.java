package com.openplan.backend.externalcalendar.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 연동 추가 요청 (ONB-07 · FIX-14) — openapi {@code createConnection}.
 *
 * <p><b>필드가 두 벌인 이유.</b> 계약이 {@code oneOf} + {@code discriminator(provider)} 다 —
 * 구글은 {@code authCode·redirectUri·state}, 애플은 {@code appleId·appPassword} 를 싣는다.
 * 애플은 서드파티용 캘린더 REST API 가 없어 CalDAV(Basic)로 붙기 때문이고, 그쪽에는 인가 단계가 아예 없다.
 *
 * <p><b>왜 {@code @NotBlank} 를 떼었는가.</b> 빈 검증은 "이 필드는 항상 필요하다"만 말할 수 있어
 * <b>제공자에 따라 갈리는 필수 조건을 표현하지 못한다.</b> 그대로 두면 애플 요청이 authCode 없음으로
 * 먼저 튕겨 계약이 허용한 모양을 서버가 거부하게 된다. 그래서 <b>검증 강도를 낮춘 게 아니라 옮겼다</b> —
 * 어느 쪽 조합인지 아는 지점(서비스)에서 판정한다. 여기서 느슨하게 두고 저기서 안 하면 그게 구멍이므로,
 * 옮긴 검증은 {@code ExternalCalendarService#create} 의 제공자 분기에 있다.
 *
 * <p>{@code provider}를 문자열로 받는 것은 알 수 없는 값에 500 이 아니라 계약된 오류를 돌려주기 위해서다.
 *
 * <p>{@code redirectUri}는 형식이 아니라 <b>검증 대상</b>이다 — 제공자가 인가 때 쓴 값과 대조하며 한 글자만
 * 달라도 교환을 거부한다. {@code state}는 인가를 시작한 것이 이 세션임을 증명한다 — 없으면 공격자가 자기
 * 캘린더의 인가 코드를 남의 세션에 붙여 <b>피해자 계정에 자기 캘린더를 연결</b>시킬 수 있다(CSRF).
 *
 * <p>{@code appPassword}는 네이버 보안설정에서 발급하는 <b>2단계 인증 애플리케이션 비밀번호</b>이며 일반
 * 로그인 비밀번호가 아니다. 암호화해 보관하고 응답·로그 어디에도 싣지 않는다.
 */
public record CreateConnectionRequest(
        @NotNull String provider,
        String authCode,
        String redirectUri,
        String state,
        String appleId,
        String appPassword) {
}
