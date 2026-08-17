package com.openplan.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 앱 주소 설정 ({@code op.app}) — 메일 링크와 OAuth 리다이렉트가 <b>같은 값</b>을 보게 하는 자리.
 *
 * <p>두 주소를 구분해 둔다. 헷갈리면 로그인은 되는데 사용자가 엉뚱한 곳에 떨어진다.
 * <ul>
 *   <li>{@link #baseUrl} — <b>사용자 브라우저가 여는 프론트 주소.</b> 메일 링크와 OAuth 성공/실패 후
 *       돌아갈 화면이 여기에 붙는다({@code /dashboard}, {@code /login?error=…}).</li>
 *   <li>{@link #apiBaseUrl} — <b>이 서버 주소.</b> OAuth 제공자에 등록한 {@code redirect_uri}가 여기에 붙는다.
 *       제공자 콘솔에 등록된 값과 <b>한 글자도 달라선 안 된다</b> — 다르면 제공자가 인가를 거부한다
 *       (be1-notes 공통 규칙: {@code {apiBaseUrl}/api/v1/auth/oauth/&#123;provider&#125;/callback}).</li>
 * </ul>
 *
 * <p>단일 오리진(D-11)으로 배포하면 두 값이 같아지지만, 로컬 개발에서는 프론트가 Vite(5173),
 * 서버가 8080이라 갈라진다. 그래서 한 값으로 합치지 않는다.
 *
 * <p>🔴 <b>배포 시 둘 다 실도메인으로 교체</b>해야 한다 — {@code cookie-secure: true}와 같은 체크리스트 항목이다.
 */
@ConfigurationProperties(prefix = "op.app")
public record AppProperties(String baseUrl, String apiBaseUrl) {

    public AppProperties {
        baseUrl = normalize(baseUrl);
        apiBaseUrl = normalize(apiBaseUrl);
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 프론트 화면 주소 조립 — 슬래시 중복·누락을 여기서 흡수한다. */
    public String frontendUrl(String path) {
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    /** 이 서버의 절대 주소 조립 (OAuth {@code redirect_uri}용). */
    public String apiUrl(String path) {
        return apiBaseUrl + (path.startsWith("/") ? path : "/" + path);
    }
}
