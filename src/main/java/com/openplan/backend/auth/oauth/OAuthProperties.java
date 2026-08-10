package com.openplan.backend.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Optional;

/**
 * 소셜 제공자 자격증명 ({@code op.oauth.clients.*}). 값은 개인 {@code .env}에만 있고 저장소에는 없다
 * (be1-notes — 3사 등록 완료 2026-07-18).
 *
 * <p><b>없어도 기동한다.</b> 자격증명이 없는 제공자는 인가 시작 시점에 걸러 실패 리다이렉트로 보낸다 —
 * 기동을 막으면 소셜 로그인을 쓰지 않는 팀원 로컬까지 함께 멈춘다(D-32와 같은 판단).
 *
 * @param clients 제공자 키({@code google}/{@code naver}/{@code kakao}) → 자격증명
 */
@ConfigurationProperties(prefix = "op.oauth")
public record OAuthProperties(Map<String, Client> clients) {

    public OAuthProperties {
        clients = clients == null ? Map.of() : clients;
    }

    /**
     * @param clientId     제공자 콘솔의 클라이언트 ID(카카오는 REST API 키)
     * @param clientSecret 클라이언트 시크릿
     */
    public record Client(String clientId, String clientSecret) {

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }

    /** 설정된 자격증명. 없거나 비어 있으면 {@link Optional#empty()}. */
    public Optional<Client> client(OAuthProviderType provider) {
        Client client = clients.get(provider.pathValue());
        return client != null && client.isConfigured() ? Optional.of(client) : Optional.empty();
    }
}
