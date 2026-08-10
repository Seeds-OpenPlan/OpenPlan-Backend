package com.openplan.backend.global.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 메일 발송 설정 ({@code op.mail}).
 *
 * <p>SMTP 접속 정보({@code spring.mail.*})와 분리해 둔다 — 저쪽은 "어떻게 보내는가"(호스트·자격증명)이고
 * 이쪽은 "무엇을 담는가"(발신 주소·링크가 가리킬 곳)다. 운영에서 Gmail→SES로 옮기면 앞의 것만 바뀐다.
 *
 * @param from    발신 주소. SMTP 계정과 다를 수 있으나, 제공자가 위장 발신을 막으므로
 *                대개 인증된 주소여야 한다(SES는 검증된 아이덴티티만 허용)
 * @param baseUrl 메일에 담을 링크의 앞부분 — <b>사용자 브라우저가 여는 프론트 주소</b>이지 API 주소가 아니다.
 *                인증 링크는 프론트 화면으로 떨어지고, 그 화면이 토큰을 들고 API를 부른다(ux-flow-map SCR-AUTH-VERIFY).
 *                끝의 {@code /}는 붙이든 말든 되도록 조립 시 정규화한다
 */
@ConfigurationProperties(prefix = "op.mail")
public record MailProperties(String from, String baseUrl) {

    public MailProperties {
        from = from == null ? "" : from.trim();
        baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** {@code baseUrl}에 경로를 이어 붙인다. 슬래시 중복·누락을 여기서 흡수한다. */
    public String link(String path) {
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }
}
