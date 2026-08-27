package com.openplan.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 계획 초안 서비스 접속 정보 ({@code op.ai.*}) — 계약 "05. Spring ↔ AI 서비스 REST 계약".
 *
 * <p><b>없어도 기동한다</b>(D-32와 같은 판단). {@code base-url}이 비어 있으면 AI 좌석을 아예 만들지 않고
 * 규칙 first-fit 이 그대로 {@code PlanPlacementPort} 를 맡는다 — AI 를 안 켠 팀원 로컬까지 멈추지 않는다.
 *
 * @param baseUrl AI 서비스 주소. 배포는 내부망 {@code http://openplan-ai:8000} (계약 §2 — 외부 미노출)
 * @param timeout 초과 시 규칙 폴백. 계약 §7 #2 제안값 20초 — 규칙 자동배치 예산(NFR-029 5초)보다 길다
 */
@ConfigurationProperties(prefix = "op.ai")
public record AiProperties(String baseUrl, Duration timeout) {

    public AiProperties {
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    /** 주소가 없으면 AI 를 쓰지 않는다 — 설정 부재가 기동 실패가 되지 않게 한다. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
