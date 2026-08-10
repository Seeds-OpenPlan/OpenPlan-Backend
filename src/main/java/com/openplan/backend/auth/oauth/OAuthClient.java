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
        return accessToken;
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
