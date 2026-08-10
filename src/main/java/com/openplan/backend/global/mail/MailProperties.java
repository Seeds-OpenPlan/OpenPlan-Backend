package com.openplan.backend.global.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 메일 발송 설정 ({@code op.mail}).
 *
 * <p>SMTP 접속 정보({@code spring.mail.*})와 분리해 둔다 — 저쪽은 "어떻게 보내는가"(호스트·자격증명)이고
 * 이쪽은 "누가 보내는가"(발신 주소)다. 운영에서 Gmail→SES로 옮기면 앞의 것만 바뀐다.
 *
 * <p>메일 링크의 앞부분은 여기 없다 — {@link com.openplan.backend.global.config.AppProperties}가 갖는다.
 * OAuth 리다이렉트와 같은 프론트 주소를 가리켜야 하므로, 두 곳에 두면 배포 때 한쪽만 바꾸고 만다.
 *
 * @param from 발신 주소. SMTP 계정과 다를 수 있으나, 제공자가 위장 발신을 막으므로
 *             대개 인증된 주소여야 한다(SES는 검증된 아이덴티티만 허용). 비우면 SMTP 계정을 쓴다
 */
@ConfigurationProperties(prefix = "op.mail")
public record MailProperties(String from) {

    public MailProperties {
        from = from == null ? "" : from.trim();
    }
}
