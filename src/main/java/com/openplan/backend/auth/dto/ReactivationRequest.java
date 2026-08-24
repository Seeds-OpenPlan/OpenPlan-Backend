package com.openplan.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 계정 재활성화 요청 (ACCT-05 · {@code POST /auth/reactivations}).
 *
 * <p>정본 openapi 는 <i>"로컬 = credentials / 소셜 = reactivationTicket 중 하나"</i>로 적고 있으나,
 * <b>{@code reactivationTicket} 의 발급 규칙이 계약 어디에도 없다</b>(AuthService.assertLoginAllowed 의
 * TODO(ST-B1-06) 가 같은 사실을 기록해 두었다). 규칙 없이 만들면 서명·만료·재사용 방지를 이 자리에서
 * 발명하게 되고, 그건 계약이 아니라 추측이 된다. 그래서 <b>자격 기반 경로만</b> 받는다.
 *
 * <p>소셜 전용 계정은 비밀번호가 없어 이 경로로 되살릴 수 없다 — 계약에 티켓 규칙이 생기면 그때
 * 필드를 더한다. 지금 상태로도 로컬 계정의 재활성화는 막히지 않는다.
 *
 * <p>형식 검증은 {@link LoginRequest} 와 같은 이유로 최소로 둔다 — 실패가 원인별로 갈라지면
 * 계정 존재 여부가 새어 나간다.
 */
public record ReactivationRequest(
        @NotBlank(message = "email is required") String email,
        @NotBlank(message = "password is required") String password) {
}
