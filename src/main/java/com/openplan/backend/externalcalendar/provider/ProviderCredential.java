package com.openplan.backend.externalcalendar.provider;

/**
 * 제공자 호출에 실을 자격증명 (ST-B1-11).
 *
 * <p><b>왜 {@code String accessToken} 이 아닌가.</b> 세 제공자의 인증 모델이 갈라져 있다. 구글·카카오는
 * Bearer 토큰 하나면 되지만, 애플 CalDAV 는 Basic 이라 <b>아이디와 비밀번호 둘</b>이 필요하다. 토큰
 * 문자열만 넘기는 서명으로는 애플 어댑터가 아이디를 얻을 길이 없어, {@code "id:secret"} 같은 규약을
 * 문자열 안에 숨기게 된다 — 그런 규약은 어디에도 적히지 않은 채 파싱 코드로만 남는다.
 *
 * <p>{@code accountIdentifier} 는 OAuth 제공자에게는 <b>쓰이지 않는다</b>. 안 쓰는 값을 넘기는 것이
 * 낭비로 보일 수 있으나, 대안은 제공자마다 다른 서명을 두는 것이고 그러면 레지스트리가 어댑터를
 * 균질하게 다루지 못한다.
 *
 * @param accountIdentifier Basic 인증의 사용자명(애플 아이디). OAuth 제공자에서는 참고값.
 * @param secret            평문 비밀 — OAuth 는 access 토큰, CalDAV 는 애플리케이션 비밀번호.
 */
public record ProviderCredential(String accountIdentifier, String secret) {

    /** OAuth 제공자 — 토큰만으로 충분하다. */
    public static ProviderCredential bearer(String accessToken) {
        return new ProviderCredential(null, accessToken);
    }

    /** CalDAV Basic — 사용자명과 비밀번호가 함께 있어야 한 번의 호출이 성립한다. */
    public static ProviderCredential basic(String username, String password) {
        return new ProviderCredential(username, password);
    }
}
