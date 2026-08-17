package com.openplan.backend.global.mail;

/**
 * 메일 발송 포트 (ST-B1-04/05 — 이메일 인증·비밀번호 재설정).
 *
 * <p><b>이름에 주의.</b> Spring 에는 이미 {@code org.springframework.mail.MailSender}가 있고
 * Spring Boot 자동 구성이 {@code mailSender}라는 이름의 빈을 등록한다. 이 포트를 {@code MailSender}로
 * 두었다가 <b>빈 이름 충돌로 컨텍스트가 기동하지 않았다</b>({@code BeanDefinitionOverrideException}).
 * 프레임워크가 이미 쓰는 이름은 피한다 — 타입 혼동도 함께 사라진다.
 *
 * <p><b>인터페이스로 둔 이유는 로컬 때문이다.</b> SMTP 자격증명은 개인 {@code .env}에만 있고
 * 저장소에는 없다. 구현을 직접 부르면 자격증명 없는 팀원 로컬에서 가입 흐름이 통째로 막힌다 —
 * {@code JwtProperties}를 조건부 등록으로 돌린 것과 같은 판단이다(D-32).
 * 자격증명이 없으면 {@link LoggingMailDispatcher}가 들어가 링크를 로그로 흘려 준다.
 *
 * <p>테스트에서도 이 자리에 기록용 가짜를 끼워 발송 여부·수신자·링크를 검증할 수 있다.
 */
public interface MailDispatcher {

    /**
     * 메일 한 통 발송. <b>동기</b>다.
     *
     * <p>비동기로 던지지 않는 이유: 이 프로젝트는 {@code TraceIdFilter}가 MDC에 traceId를 심어
     * 로그를 요청 단위로 묶는데, 별도 스레드로 넘기면 그 traceId가 끊겨 "누구의 발송이 실패했는지"를
     * 로그에서 되짚을 수 없다. 발송량이 하루 수십 통 규모라 요청 안에서 처리해도 무리가 없고,
     * 나중에 비동기가 필요해지면 이 구현 하나만 바꾸면 된다.
     *
     * @param to      수신 주소
     * @param subject 제목
     * @param body    본문(평문)
     * @throws MailDeliveryException 발송 실패 — 호출부가 사용자 응답을 결정한다
     */
    void send(String to, String subject, String body);
}
