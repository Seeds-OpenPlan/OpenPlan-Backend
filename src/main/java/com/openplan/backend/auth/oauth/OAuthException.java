package com.openplan.backend.auth.oauth;

/**
 * 소셜 인증 실패 — 제공자 거부·state 불일치·토큰 교환 실패·이메일 미제공 등.
 *
 * <p><b>오류 봉투로 나가지 않는다.</b> 이 흐름의 사용자는 브라우저 리다이렉트를 타고 있으므로
 * JSON 401을 받아도 볼 수 없다. 컨트롤러가 이 예외를 잡아 {@code /login?error=…} 302로 바꾼다
 * (ST-B1-03 AC1 · 쿼리스트링 토큰 전달 금지 — R8).
 *
 * <p>{@link #errorCode}는 리다이렉트 쿼리에 실릴 값이다. 대부분 {@code E-AUTH-010}이지만,
 * 이미 로컬 계정이 있는 이메일이면 {@code E-AUTH-003}처럼 사용자가 무엇을 해야 할지 알 수 있는 코드를 싣는다.
 */
public class OAuthException extends RuntimeException {

    private final String errorCode;

    public OAuthException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OAuthException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
