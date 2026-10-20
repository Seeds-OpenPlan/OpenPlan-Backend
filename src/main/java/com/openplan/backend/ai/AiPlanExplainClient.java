package com.openplan.backend.ai;

import com.openplan.backend.rule.model.PlanSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * AI 계획 설명 서비스 호출 ({@code POST /plans/explain}).
 *
 * <p>{@link AiPlanDraftClient} 와 같은 접합이지만, <b>이쪽은 규칙 기반 대체문을 만들 수 없다.</b>
 * 초안은 겹침·마감 판정이 first-fit 으로 대체 가능하지만(계약 §4 폴백), "이 배치를 사람 말로
 * 풀어 설명"하는 일은 규칙 엔진이 대신할 수 없는 자연어 생성이다 — 실패하면 그대로 오류를 올린다
 * (호출자 {@code PlanExplanationService} 가 503 으로 변환).
 */
public class AiPlanExplainClient {

    private static final Logger log = LoggerFactory.getLogger(AiPlanExplainClient.class);

    private final RestClient restClient;

    public AiPlanExplainClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 요청 본문 — AI 계약 ExplainRequest. issues 는 규칙 엔진이 낸 reason 문자열 그대로(새 판정 금지).
     * public인 이유: {@link AiPlanDraftClient}의 동료 레코드들과 달리 이 클래스는 호출자
     * ({@code PlanExplanationService})가 <b>다른 패키지(weeklyplan.service)</b>에 있다 — AI 초안은
     * 같은 패키지의 {@code AiPlacementAdapter}만 호출해 package-private으로 충분했지만, 설명은
     * 폴백 어댑터 없이 서비스 계층이 직접 부른다.
     */
    public record ExplainRequest(PlanSnapshot snapshot, List<String> issues) {
    }

    /** 응답 본문 — AI 계약 ExplainResponse. */
    public record ExplainResponse(String explanation, Meta meta) {

        public record Meta(String model, Integer latencyMs) {
        }
    }

    /**
     * 설명을 받아 온다. 실패는 전부 {@link AiUnavailableException} 으로 좁혀 던진다 — 호출자가
     * 구분할 일은 없다(§4 실패 응답 표와 동형: 502·503·504·타임아웃 모두 "AI 가 답을 못 줬다").
     */
    public ExplainResponse explain(PlanSnapshot snapshot, List<String> issues) {
        try {
            ExplainResponse body = restClient.post()
                    .uri("/plans/explain")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ExplainRequest(snapshot, issues))
                    .retrieve()
                    .body(ExplainResponse.class);

            if (body == null || body.explanation() == null || body.explanation().isBlank()) {
                throw new AiUnavailableException("응답 본문이 비었다", null);
            }
            if (body.meta() != null) {
                log.info("AI 설명 수신: model={} latencyMs={} 길이={}자",
                        body.meta().model(), body.meta().latencyMs(), body.explanation().length());
            }
            return body;
        } catch (AiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 연결 거부·타임아웃·5xx·422 가 모두 여기로 온다 — AiPlanDraftClient 와 같은 이유로
            // 원인을 메시지에 담는다(디버깅 시 "실패했다"만 남는 로그를 피하기 위함).
            throw new AiUnavailableException(
                    "AI 설명 호출 실패: " + ex.getClass().getSimpleName() + " — " + ex.getMessage(), ex);
        }
    }

    /**
     * AI 가 답을 못 준 모든 경우. 대체문이 없으므로 받는 쪽이 할 일은 503 으로 알리는 것뿐이다.
     * public — {@code PlanExplanationService}(다른 패키지)가 catch 해야 한다.
     */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
