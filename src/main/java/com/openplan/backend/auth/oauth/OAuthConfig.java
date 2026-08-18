package com.openplan.backend.auth.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 소셜 로그인 설정 바인딩 (ST-B1-03).
 *
 * <p>자격증명이 없어도 등록된다 — 없는 제공자는 인가 시작 시점에 걸러 실패 리다이렉트로 보낸다.
 * 기동을 막으면 소셜 로그인을 쓰지 않는 팀원 로컬까지 함께 멈춘다(D-32와 같은 판단).
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthConfig {
}
