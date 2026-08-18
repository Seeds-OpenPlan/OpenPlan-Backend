package com.openplan.backend.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.openplan.backend.user.domain.SocialProvider;

import java.util.Locale;
import java.util.Optional;

/**
 * 소셜 제공자별 OAuth 2.0 엔드포인트와 응답 해석 규칙 (ST-B1-03 · AUTH-02).
 *
 * <p><b>엔드포인트를 설정이 아니라 코드에 둔다.</b> 제공자가 바꾸지 않는 공개 주소이고, 설정으로 빼면
 * 세 환경(.env·yaml·제공자 콘솔)에 같은 값이 흩어져 어긋날 여지만 는다. 환경마다 달라지는 것은
 * 클라이언트 자격증명뿐이며 그것만 {@link OAuthProperties}로 뺀다.
 *
 * <p><b>세 제공자의 사용자 정보 응답 모양이 전부 다르다</b> — 구글은 평면, 네이버는 {@code response} 아래,
 * 카카오는 {@code kakao_account} 아래다. 그 차이를 여기서 흡수해 상위 코드는 {@link OAuthUserInfo} 하나만 본다.
 *
 * <p>🔴 <b>이메일이 없을 수 있다.</b> 카카오는 이메일이 <b>선택 동의</b>라 사용자가 거부하면 응답에 없고
 * (be1-notes "이메일 미제공 케이스 서버 대비"), 네이버도 제공 항목 설정에 달렸다. {@code users.email}은
 * NOT NULL UNIQUE라 이메일 없이는 계정을 만들 수 없으므로, 없으면 로그인을 성립시키지 않는다
 * (판정은 {@code OAuthLoginService}). 이메일을 필수화하려면 카카오 비즈앱 전환+검수가 필요하다 — 미결.
 */
public enum OAuthProviderType {

    GOOGLE(SocialProvider.GOOGLE,
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/oauth2/v3/userinfo",
            "openid email profile") {
        @Override
        OAuthUserInfo parse(JsonNode root) {
            return new OAuthUserInfo(text(root, "sub"), text(root, "email"));
        }
    },

    /** 응답이 {@code {resultcode, message, response:{id, email, ...}}} 로 한 겹 감싸여 있다. */
    NAVER(SocialProvider.NAVER,
            "https://nid.naver.com/oauth2.0/authorize",
            "https://nid.naver.com/oauth2.0/token",
            "https://openapi.naver.com/v1/nid/me",
            "") {
        @Override
        OAuthUserInfo parse(JsonNode root) {
            JsonNode response = root.path("response");
            return new OAuthUserInfo(text(response, "id"), text(response, "email"));
        }
    },

    /** 식별자는 최상위 {@code id}, 이메일은 동의 항목이라 {@code kakao_account} 아래에 있다(없을 수 있다). */
    KAKAO(SocialProvider.KAKAO,
            "https://kauth.kakao.com/oauth/authorize",
            "https://kauth.kakao.com/oauth/token",
            "https://kapi.kakao.com/v2/user/me",
            "account_email") {
        @Override
        OAuthUserInfo parse(JsonNode root) {
            return new OAuthUserInfo(text(root, "id"), text(root.path("kakao_account"), "email"));
        }
    };

    private final SocialProvider socialProvider;
    private final String authorizationUri;
    private final String tokenUri;
    private final String userInfoUri;
    private final String scope;

    OAuthProviderType(SocialProvider socialProvider, String authorizationUri, String tokenUri,
                      String userInfoUri, String scope) {
        this.socialProvider = socialProvider;
        this.authorizationUri = authorizationUri;
        this.tokenUri = tokenUri;
        this.userInfoUri = userInfoUri;
        this.scope = scope;
    }

    /** 사용자 정보 응답 → 공통 형태. 제공자별 중첩 구조를 여기서 흡수한다. */
    abstract OAuthUserInfo parse(JsonNode root);

    /**
     * 경로 변수({@code google}/{@code naver}/{@code kakao}) → 열거값.
     * openapi {@code OAuthProvider} 파라미터가 소문자 enum으로 고정돼 있다.
     *
     * @return 알 수 없는 값이면 {@link Optional#empty()} — 예외 대신 빈 값인 이유는
     *         컨트롤러가 이것을 오류 봉투가 아니라 <b>302 리다이렉트</b>로 처리해야 하기 때문이다
     */
    public static Optional<OAuthProviderType> from(String pathValue) {
        if (pathValue == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(pathValue.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 경로 변수 표기(소문자) — 리다이렉트 URI 조립에 쓴다. */
    public String pathValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public SocialProvider socialProvider() {
        return socialProvider;
    }

    public String authorizationUri() {
        return authorizationUri;
    }

    public String tokenUri() {
        return tokenUri;
    }

    public String userInfoUri() {
        return userInfoUri;
    }

    /** 빈 문자열이면 인가 요청에 {@code scope}를 싣지 않는다(네이버는 콘솔 설정을 따른다). */
    public String scope() {
        return scope;
    }

    /** 값이 없거나 빈 문자열이면 null — 상위에서 "미제공"으로 판정한다. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
