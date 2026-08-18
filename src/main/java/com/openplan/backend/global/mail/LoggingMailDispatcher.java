package com.openplan.backend.global.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMTP 자격증명이 없을 때 들어가는 대체 발송기 — <b>보내지 않고 로그로 흘린다.</b>
 *
 * <p><b>왜 필요한가.</b> 자격증명은 개인 {@code .env}에만 있고 저장소에는 없다. 없다고 기동을 막거나
 * 가입 흐름에서 예외를 던지면, 메일을 쓸 일 없는 팀원 2인의 로컬이 인증 작업 때문에 막힌다.
 * "설정이 없으면 기능이 조용히 축소될 뿐 남의 작업을 깨지 않는다" — {@code JwtAuthConfig} 조건부 등록과 같은 판단(D-32).
 *
 * <p>덤으로 로컬 개발에 실용적이다. 인증 링크가 콘솔에 그대로 찍히므로 메일함 없이 흐름을 끝까지 밟을 수 있다.
 *
 * <p><b>운영에서 이 구현이 선택되면 안 된다.</b> 그래서 기동 시 WARN을 한 번 남긴다 —
 * 배포 후 "메일이 안 와요"의 원인이 대개 이것이고, 로그에 흔적이 없으면 찾는 데 오래 걸린다.
 */
public class LoggingMailDispatcher implements MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailDispatcher.class);

    public LoggingMailDispatcher() {
        log.warn("SMTP 자격증명이 없어 메일을 실제로 보내지 않습니다 — 본문을 로그로만 남깁니다. "
                + "실제 발송이 필요하면 .env 의 MAIL_USERNAME·MAIL_PASSWORD 를 설정하십시오.");
    }

    @Override
    public void send(String to, String subject, String body) {
        log.info("""
                [메일 미발송 — 자격증명 없음]
                  to      : {}
                  subject : {}
                  body    :
                {}""", to, subject, body);
    }
}
