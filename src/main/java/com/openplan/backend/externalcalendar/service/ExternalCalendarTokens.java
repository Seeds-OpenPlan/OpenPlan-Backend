package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthException;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.auth.oauth.OAuthTokenSet;
import com.openplan.backend.externalcalendar.crypto.ExternalTokenCipher;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.provider.ProviderCredential;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 연동의 제공자 access 토큰을 <b>쓸 수 있는 상태로</b> 꺼내 준다 (ST-B1-11).
 *
 * <p>호출 측이 복호·만료 판정·갱신을 각자 하면 세 곳에서 서로 다르게 틀린다. 그래서 "지금 쓸 수 있는
 * 평문 access 토큰"을 돌려주는 창구를 하나로 둔다.
 *
 * <p><b>평문 토큰은 반환값으로만 존재한다</b> — 필드에 담지 않고 로그에도 남기지 않는다.
 */
@Component
public class ExternalCalendarTokens {

    private static final Logger log = LoggerFactory.getLogger(ExternalCalendarTokens.class);

    /**
     * 만료 직전을 만료로 친다. 조회에 10초까지 걸릴 수 있어(응답 타임아웃) 경계에서 통과시키면
     * <b>호출 도중에 만료</b>되는 경로가 생긴다.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final ExternalTokenCipher cipher;
    private final OAuthClient oauthClient;
    private final OAuthProperties oauthProperties;
    private final UserClock userClock;

    public ExternalCalendarTokens(ExternalTokenCipher cipher, OAuthClient oauthClient,
                                  OAuthProperties oauthProperties, UserClock userClock) {
        this.cipher = cipher;
        this.oauthClient = oauthClient;
        this.oauthProperties = oauthProperties;
        this.userClock = userClock;
    }

    /**
     * 지금 제공자 호출에 쓸 수 있는 평문 access 토큰.
     *
     * <p>만료됐고 refresh 토큰이 있으면 갱신해 연동에 반영한다(같은 트랜잭션의 더티 체킹으로 저장).
     * 갱신이 불가능하거나 실패하면 502 E-EXT-001 — 사용자에게는 "제공자 연동 실패"로 보이고,
     * 실제 복구 수단은 재연동이다.
     */
    public String usableAccessToken(ExternalCalendarConnection connection) {
        if (!isExpired(connection)) {
            return cipher.decrypt(connection.getAccessTokenEnc());
        }
        if (connection.getRefreshTokenEnc() == null) {
            // 제공자가 refresh 를 주지 않았거나 동의가 그것을 포함하지 않았다 — 재연동 외 방법이 없다.
            log.warn("외부 캘린더 토큰 만료 · refresh 없음: connectionId={}", connection.getId());
            throw providerFailure(connection);
        }
        OAuthProperties.Client client = oauthProperties.client(connection.getProvider().oauthProvider())
                .orElseThrow(() -> providerFailure(connection));
        try {
            OAuthTokenSet refreshed = oauthClient.refreshAccessToken(
                    connection.getProvider().oauthProvider(), client,
                    cipher.decrypt(connection.getRefreshTokenEnc()));

            connection.refreshTokens(
                    cipher.encrypt(refreshed.accessToken()),
                    cipher.encrypt(refreshed.refreshToken()),
                    expiresAt(refreshed.expiresInSeconds()));
            return refreshed.accessToken();
        } catch (OAuthException e) {
            log.warn("외부 캘린더 토큰 갱신 실패: connectionId={}", connection.getId(), e);
            throw providerFailure(connection);
        }
    }

    /**
     * 지금 제공자 호출에 실을 수 있는 자격증명.
     *
     * <p>OAuth 제공자는 Bearer 토큰 하나지만 애플 CalDAV 는 Basic 이라 아이디가 함께 필요하다.
     * 그 아이디는 연결의 {@code accountIdentifier} 에 이미 있다 — 호출 측이 매번 조립하게 두면
     * 어느 한 곳에서 빠뜨린다.
     *
     * <p>CalDAV 연결은 {@code token_expires_at} 이 null 이라 {@link #usableAccessToken} 의 만료 판정을
     * 그대로 통과하고 복호만 일어난다. <b>앱 전용 비밀번호는 만료되지 않기 때문이다</b> —
     * 사용자가 발급처에서 회수할 때까지 유효하고, 회수되면 401 이 올라온다.
     */
    public ProviderCredential usableCredential(ExternalCalendarConnection connection) {
        String secret = usableAccessToken(connection);
        return connection.getProvider().usesOAuth()
                ? ProviderCredential.bearer(secret)
                : ProviderCredential.basic(connection.getAccountIdentifier(), secret);
    }

    /** 최초 연결 시 토큰을 암호문으로 바꾼다. */
    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    /** {@code expires_in}(초) → 절대 시각. 제공자가 알려주지 않으면 null. */
    public Instant expiresAt(Long expiresInSeconds) {
        return expiresInSeconds == null ? null : userClock.now().plusSeconds(expiresInSeconds);
    }

    /** 암호화 키 미설정을 제공자 오류로 오인하지 않도록 연결 시작 전에 확인한다. */
    public boolean isCipherConfigured() {
        return cipher.isConfigured();
    }

    private boolean isExpired(ExternalCalendarConnection connection) {
        Instant expiresAt = connection.getTokenExpiresAt();
        if (expiresAt == null) {
            // 만료를 모르면 일단 쓴다 — 실패하면 제공자가 401 로 알려주고 그것이 502 로 올라간다.
            return false;
        }
        return !userClock.now().plus(EXPIRY_MARGIN).isBefore(expiresAt);
    }

    private OpenPlanException providerFailure(ExternalCalendarConnection connection) {
        return new OpenPlanException(ErrorCode.E_EXT_001,
                Map.of("provider", connection.getProvider().name()));
    }
}
