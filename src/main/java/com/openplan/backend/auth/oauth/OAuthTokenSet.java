package com.openplan.backend.auth.oauth;

/**
 * 인가 코드 교환 결과 전량 (ST-B1-11).
 *
 * <p>소셜 <b>로그인</b>(ST-B1-03)은 access 토큰으로 사용자 정보를 한 번 읽고 버리면 끝이라 나머지를
 * 쓰지 않았다. 외부 캘린더는 연동이 유지되는 동안 제공자 API를 <b>계속</b> 호출하므로 refresh 토큰과
 * 만료 시각이 필요하다 — 그래서 교환 응답을 통째로 담는 형태를 따로 둔다.
 *
 * @param accessToken       필수. 없으면 교환 실패로 판정한다.
 * @param refreshToken      제공자·동의 조건에 따라 없을 수 있다(구글은 첫 동의에서만 준다).
 *                          없으면 만료 후 재연동이 필요하다.
 * @param expiresInSeconds  access 토큰의 잔여 수명(초). 제공자가 알려주지 않으면 null.
 */
public record OAuthTokenSet(String accessToken, String refreshToken, Long expiresInSeconds) {
}
