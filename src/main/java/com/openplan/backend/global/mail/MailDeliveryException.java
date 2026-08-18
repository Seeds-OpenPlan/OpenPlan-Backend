package com.openplan.backend.global.mail;

/**
 * 메일 발송 실패. SMTP 예외를 도메인 경계 안쪽 타입으로 감싸, 호출부가
 * {@code jakarta.mail}·{@code org.springframework.mail} 타입을 알지 않아도 되게 한다.
 *
 * <p><b>이 예외를 사용자 응답으로 그대로 흘리지 말 것.</b> 발송 실패는 계정 존재 여부와 무관하게
 * 같은 응답(202)으로 수렴해야 하는 경로가 있다(비밀번호 재설정 — 계정 열거 방지).
 * 호출부가 로그를 남기고 응답을 정하는 것이 규약이다.
 */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
