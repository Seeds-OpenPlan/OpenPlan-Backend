package com.openplan.backend.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.openplan.backend.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 제공자와의 서버 간 통신 — 인가 코드 교환과 사용자 정보 조회 (ST-B1-03).
 *
 * <p>Spring Security의 {@code oauth2Login}을 쓰지 않고 직접 부른다. 이유가 셋이다:
 * ⑴ 이 서버는 무상태인데 그쪽 기본 구현은 인가 요청을 <b>세션에 저장</b>한다
 * ⑵ 명세가 경로를 {@code /auth/oauth/&#123;provider&#125;}로 못박아 두었고 그 값이 이미 제공자 콘솔에 등록돼 있다
 * ⑶ 네이버·카카오는 내장 제공자가 아니라 어차피 전 항목을 직접 적어야 한다.
 * 얻는 것보다 맞추는 비용이 커서 직접 부르는 편이 짧다.
 *
 * <p><b>제공자 응답 본문을 로그에 남기지 않는다.</b> 토큰과 개인정보가 들어 있다.
 */
@Component
public class OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthClient.class);

    private final RestClient restClient;

    public OAuthClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /**
     * 인가 코드 → access 토큰.
     *
     * <p>{@code redirect_uri}를 다시 싣는 것은 형식이 아니라 <b>검증 대상</b>이다 — 제공자가 인가 때 쓴 값과
     * 대조하며, 한 글자라도 다르면 교환을 거부한다. 그래서 인가 시작과 콜백이 같은 조립 함수를 쓴다.
     */
    public String exchangeCodeForAccessToken(OAuthProviderType provider, OAuthProperties.Client client,
                                             String code, String redirectUri) {
        return exchangeCodeForTokens(provider, client, code, redirectUri).accessToken();
    }

    /**
     * 인가 코드 → 토큰 전량(access·refresh·만료).
     *
     * <p>로그인(ST-B1-03)은 access 하나면 끝나지만 외부 캘린더(ST-B1-11)는 연동이 유지되는 동안
     * 제공자를 계속 호출해야 해서 refresh 토큰이 필요하다. 교환 절차 자체는 같으므로
     * {@link #exchangeCodeForAccessToken}이 이 메서드에 얹혀 있다 — 요청 조립이 갈라지면
     * 한쪽만 고쳐지는 경로가 생긴다.
     */
    public OAuthTokenSet exchangeCodeForTokens(OAuthProviderType provider, OAuthProperties.Client client,
                                               String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        JsonNode body = post(provider, form);
        String accessToken = OAuthProviderType.text(body, "access_token");
        if (accessToken == null) {
            // 제공자는 200으로 응답하면서 본문에 error 를 담기도 한다 — 상태코드만 믿지 않는다.
            log.warn("소셜 토큰 교환 응답에 access_token 없음: provider={} error={}",
                    provider, OAuthProviderType.text(body, "error"));
            throw new OAuthException(ErrorCode.E_AUTH_010.code(), "토큰 교환 실패: " + provider);
        }
        return new OAuthTokenSet(
                accessToken,
                OAuthProviderType.text(body, "refresh_token"),
                expiresIn(body));
    }

    /** {@code expires_in}은 초 단위 정수지만 문자열로 주는 제공자가 있어 텍스트로 읽고 변환한다. */
    private static Long expiresIn(JsonNode body) {
        String raw = OAuthProviderType.text(body, "expires_in");
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;   // 만료를 모르면 갱신 판단을 호출 실패에 맡긴다 — 교환 자체를 깨뜨리지 않는다.
        }
    }

    /**
     * refresh 토큰 → 새 access 토큰 (ST-B1-11).
     *
     * <p>연동은 <b>연결된 채로 오래 산다</b> — 첫 교환에서 받은 access 토큰은 대개 몇 시간이면 만료되므로
     * 조회 때마다 유효성을 확인하고 필요하면 여기서 갱신한다. 제공자는 새 refresh 토큰을 주기도 하고
     * 주지 않기도 하는데, 주지 않았다고 기존 것을 지우면 <b>다음 갱신이 영영 불가능</b>해진다
     * (그 처리는 {@code ExternalCalendarConnection.refreshTokens}).
     */
    public OAuthTokenSet refreshAccessToken(OAuthProviderType provider, OAuthProperties.Client client,
                                            String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("refresh_token", refreshToken);

        JsonNode body = post(provider, form);
        String accessToken = OAuthProviderType.text(body, "access_token");
        if (accessToken == null) {
            log.warn("소셜 토큰 갱신 응답에 access_token 없음: provider={} error={}",
                    provider, OAuthProviderType.text(body, "error"));
            throw new OAuthException(ErrorCode.E_AUTH_010.code(), "토큰 갱신 실패: " + provider);
        }
        return new OAuthTokenSet(
                accessToken,
                OAuthProviderType.text(body, "refresh_token"),
                expiresIn(body));
    }

    /** access 토큰 → 사용자 식별 정보. 제공자별 응답 차이는 {@link OAuthProviderType#parse}가 흡수한다. */
    public OAuthUserInfo fetchUserInfo(OAuthProviderType provider, String accessToken) {
        try {
            JsonNode body = restClient.get()
                    .uri(provider.userInfoUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new OAuthException(ErrorCode.E_AUTH_010.code(), "사용자 정보 응답 없음: " + provider);
            }
            OAuthUserInfo info = provider.parse(body);
            if (info.providerUserId() == null) {
                throw new OAuthException(ErrorCode.E_AUTH_010.code(), "사용자 식별자 없음: " + provider);
            }
            return info;
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            log.warn("소셜 사용자 정보 조회 실패: provider={}", provider, e);
            throw new OAuthException(ErrorCode.E_AUTH_010.code(), "사용자 정보 조회 실패: " + provider, e);
        }
    }

    private JsonNode post(OAuthProviderType provider, MultiValueMap<String, String> form) {
        try {
            JsonNode body = restClient.post()
                    .uri(provider.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new OAuthException(ErrorCode.E_AUTH_010.code(), "토큰 응답 없음: " + provider);
            }
            return body;
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            log.warn("소셜 토큰 교환 실패: provider={}", provider, e);
            throw new OAuthException(ErrorCode.E_AUTH_010.code(), "토큰 교환 실패: " + provider, e);
        }
    }
}
