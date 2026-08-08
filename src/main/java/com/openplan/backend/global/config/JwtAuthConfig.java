package com.openplan.backend.global.config;

import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.global.security.JwtProperties;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.global.time.UserClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 인증 배선 (D-16 4주차 · ADR-0010) — <b>dev 스텁이 꺼져 있을 때만</b> 등록된다.
 *
 * <p><b>왜 조건부인가.</b> {@link JwtProperties}는 시크릿이 32바이트 미만이면 기동을 실패시킨다
 * (약한 키로 조용히 뜨는 것보다 못 뜨는 편이 낫다). 그런데 이 빈이 무조건 등록되면
 * <b>dev 스텁으로 도는 팀원 로컬이 {@code JWT_SECRET} 없이는 서버를 못 띄우게 된다.</b>
 * 스텁 프로파일에서는 JWT 경로 자체가 쓰이지 않으므로, 아예 등록하지 않아 그 강제를 없앤다.
 *
 * <p>{@code matchIfMissing = true} — 설정이 누락됐을 때의 기본은 <b>운영 기준</b>(JWT 활성)이다.
 * {@code SecurityConfig}가 같은 방향을 택한 것과 일치한다: 누락의 결과가 "인증 없음"이 되면 안 된다.
 *
 * <p>서비스·쿠키 조립기는 {@code @Component} 스캔 대신 여기서 빈으로 만든다 — 스캔에 맡기면
 * 조건과 무관하게 올라와 위 강제가 되살아난다.
 *
 * <p><b>필터는 빈으로 두지 않는다.</b> Spring Boot는 {@code Filter} 타입 빈을 서블릿 체인에 자동 등록해
 * 보안 체인과 <b>이중 실행</b>된다. {@code SecurityConfig}가 {@code DevUserAuthFilter}를 빈으로 노출하지 않고
 * 직접 생성하는 것과 같은 이유로, {@code JwtCookieAuthFilter}도 {@code SecurityConfig}가 생성한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "op.auth", name = "dev-stub", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtAuthConfig {

    @Bean
    public JwtService jwtService(JwtProperties props, UserClock clock) {
        return new JwtService(props, clock);
    }

    /** 로그인·로그아웃·갱신(①)이 Set-Cookie 를 만들 때 쓴다. ⓪ 시점엔 필터가 읽기만 한다. */
    @Bean
    public AuthCookies authCookies(JwtProperties props) {
        return new AuthCookies(props);
    }
}
