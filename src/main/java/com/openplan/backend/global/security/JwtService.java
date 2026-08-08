package com.openplan.backend.global.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openplan.backend.global.time.UserClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 토큰 발급·검증 (D-16 4주차 · ADR-0010).
 *
 * <p><b>access 와 refresh 는 성격이 다르다.</b>
 * <ul>
 *   <li><b>access</b> = 서명된 JWT. 무상태로 매 요청 검증하며 DB를 보지 않는다. 그래서 폐기가 안 되고,
 *       그 대가로 수명을 짧게 둔다.</li>
 *   <li><b>refresh</b> = 서명 토큰이 아니라 <b>불투명 난수</b>. 해시만 {@code auth_sessions.refresh_token_hash}에
 *       저장한다(그 컬럼이 UNIQUE인 이유). JWT로 만들면 DB 조회 없이도 유효해 보여서
 *       <b>회전·폐기·재사용 탐지가 불가능</b>해진다 — AUTH-08이 요구하는 것은 폐기 가능한 refresh다.</li>
 * </ul>
 *
 * <p>이 클래스는 <b>토큰 자체</b>만 다룬다. 세션 행 생성·회전·재사용 탐지는 로그인/갱신 서비스 몫이며,
 * 여기서는 그쪽이 쓸 재료(원문·해시·만료)를 만들어 준다.
 *
 * <p>시각은 {@link UserClock}을 경유한다 — 이 프로젝트의 단일 시간 소스 seam(C4)이라
 * 테스트에서 만료 경계를 결정적으로 고정할 수 있다. {@code java.time.Clock} 빈은 이 앱에 없다.
 *
 * <p>서명 라이브러리는 Nimbus를 직접 쓴다 — {@code spring-boot-starter-oauth2-client}가
 * {@code spring-security-oauth2-jose}를 통해 이미 끌어오므로 <b>build.gradle 변경이 없다</b>.
 */
public class JwtService {

    /** 토큰 종류 구분 클레임. access 를 refresh 자리에 밀어넣는 혼동을 서명 단계에서 끊는다. */
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "at";

    /** refresh 원문 바이트 수. 128비트로는 충분하나 여유를 둔다. */
    private static final int REFRESH_BYTES = 32;

    private final JwtProperties props;
    private final UserClock clock;
    private final SecureRandom random = new SecureRandom();

    public JwtService(JwtProperties props, UserClock clock) {
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- access

    /**
     * access 토큰 발급. subject 는 {@code users.user_id} 이며, 필터가 이 값을 그대로
     * SecurityContext principal(UUID)로 심는다 — dev 스텁과 동일한 계약이라 도메인 코드는 무변경이다.
     */
    public String issueAccessToken(UUID userId) {
        Instant now = clock.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(props.issuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.accessTtl())))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secretBytes()));
        } catch (JOSEException e) {
            // 키 길이는 JwtProperties 가 기동 시 보증한다. 여기 오면 설정이 아니라 코드 문제다.
            throw new IllegalStateException("access 토큰 서명 실패", e);
        }
        return jwt.serialize();
    }

    /**
     * access 토큰 검증. <b>실패를 예외로 던지지 않고 빈 값으로 돌려준다</b> — 만료된 토큰으로 오는 요청은
     * 비정상이 아니라 정상 흐름(→ 401 → FE가 token-refresh)이며, 예외로 만들면 로그가 소음으로 덮인다.
     *
     * @return 유효하면 subject(userId), 아니면 {@link Optional#empty()}
     */
    public Optional<UUID> parseAccessToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secretBytes()))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!TYPE_ACCESS.equals(claims.getStringClaim(CLAIM_TYPE))) {
                return Optional.empty();   // refresh 를 access 로 쓰려는 시도
            }
            if (!props.issuer().equals(claims.getIssuer())) {
                return Optional.empty();   // 다른 환경 토큰
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(clock.now())) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (Exception e) {
            // 파싱·서명·UUID 형식 어느 실패든 결론은 같다: 이 요청은 미인증이다.
            return Optional.empty();
        }
    }

    // --------------------------------------------------------------- refresh

    /**
     * refresh 원문 생성. 원문은 <b>쿠키로만</b> 나가고 서버에는 남기지 않는다.
     * 저장은 {@link #hashRefreshToken(String)} 결과만 한다 — DB가 유출돼도 토큰을 복원할 수 없다.
     */
    public String issueRefreshToken() {
        byte[] buf = new byte[REFRESH_BYTES];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * refresh 해시. {@code auth_sessions.refresh_token_hash}(VARCHAR(255) UNIQUE)에 들어간다.
     *
     * <p>비밀번호와 달리 <b>BCrypt를 쓰지 않는다.</b> refresh 는 고엔트로피 난수라 사전공격 대상이 아니고,
     * 조회가 해시 일치로 이뤄져야 하는데(UNIQUE 인덱스 탐색) BCrypt는 salt 때문에 조회가 불가능하다.
     */
    public String hashRefreshToken(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    /** 새 세션의 {@code expires_at}. 로그인·회전 양쪽이 같은 기준을 쓰도록 여기서만 계산한다. */
    public Instant refreshExpiresAt() {
        return clock.now().plus(props.refreshTtl());
    }

    private byte[] secretBytes() {
        return props.secret().getBytes(StandardCharsets.UTF_8);
    }
}
