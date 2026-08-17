package com.openplan.backend.auth.token;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 링크 토큰 설정 바인딩 (ST-B1-04/05).
 *
 * <p>{@code JwtProperties}와 달리 조건 없이 등록된다 — 이메일 인증은 dev 스텁 환경에서도 동작해야 한다.
 */
@Configuration
@EnableConfigurationProperties(AuthTokenProperties.class)
public class AuthTokenConfig {
}
