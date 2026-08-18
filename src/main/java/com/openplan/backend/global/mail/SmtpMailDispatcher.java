package com.openplan.backend.global.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 발송 구현 (Gmail 앱 비밀번호 — be1-notes "SMTP 등록 완료 2026-07-18").
 * 운영은 AWS SES로 옮긴다(프로덕션 액세스 승인 필요) — 호스트·자격증명만 바뀌므로 이 클래스는 그대로다.
 *
 * <p>본문은 평문이다. HTML 메일은 렌더링 편차·스팸 판정·이스케이프를 모두 떠안는데,
 * 지금 보내는 것은 <b>링크 한 줄이 전부</b>라 평문으로 충분하다.
 */
public class SmtpMailDispatcher implements MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailDispatcher.class);

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailDispatcher(JavaMailSender javaMailSender, String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            javaMailSender.send(message);
            // 수신 주소는 개인정보라 전체를 남기지 않는다 — 실패 추적에 필요한 만큼만.
            log.info("메일 발송 완료: to={} subject={}", maskAddress(to), subject);
        } catch (MailException e) {
            log.error("메일 발송 실패: to={} subject={}", maskAddress(to), subject, e);
            throw new MailDeliveryException("메일 발송 실패", e);
        }
    }

    /** {@code ab***@example.com} 형태. 장애 대응에 필요한 도메인·앞자리만 남긴다. */
    static String maskAddress(String address) {
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = address.substring(0, at);
        String head = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return head + "***" + address.substring(at);
    }
}
