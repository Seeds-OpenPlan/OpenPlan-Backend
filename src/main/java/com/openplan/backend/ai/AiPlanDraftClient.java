package com.openplan.backend.ai;

import com.openplan.backend.rule.model.PlanSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI 계획 초안 서비스 호출 ({@code POST /plans/draft}) — 계약 §3·§4.
 *
 * <p><b>이 클래스는 판정하지 않는다.</b> 초안을 받아 오기만 하고, 겹침·마감 위반은 규칙 엔진이 잡는다
 * (계약 §4 — "AI 응답은 확정이 아니다"). 원칙: <b>만드는 건 AI, 판정은 규칙.</b>
 *
 * <p><b>실패를 예외 하나로 좁힌다.</b> 호출자({@link AiPlacementAdapter})가 봐야 하는 것은
 * "AI 가 답을 못 줬다" 하나뿐이고, 그때 할 일은 언제나 규칙 폴백이다(계약 §4 실패 응답 표).
 * 상태코드별 구분은 여기서 로그로만 남긴다.
 */
public class AiPlanDraftClient {

    private static final Logger log = LoggerFactory.getLogger(AiPlanDraftClient.class);

    private final RestClient restClient;

    public AiPlanDraftClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 요청 본문 (계약 §3). {@code tasksToPlace} 는 Spring 이 명시한다 — AI 가 대상을 고르지 않는다(§7 #3). */
    record DraftRequest(PlanSnapshot snapshot, List<UUID> tasksToPlace) {
    }

    /** 응답 본문 (계약 §4). {@code meta} 는 관측용이라 사용자에게 안 나간다. */
    record DraftResponse(List<ProposedBlock> proposedBlocks, List<UUID> unplacedTaskIds,
                         String reason, Meta meta) {

        /** {@code blockId} 가 없다 — 아직 저장되지 않은 제안이라 ID 는 Spring 이 저장 시 만든다(계약 §4). */
        record ProposedBlock(String type, UUID taskId, UUID scheduleId, Instant startAt, Instant endAt) {
        }

        record Meta(String model, Integer latencyMs) {
        }
    }

    /**
     * 초안을 받아 온다. 실패는 전부 {@link AiUnavailableException} 으로 좁혀 던진다.
     *
     * <p>🔴 422 본문은 <b>파싱하지 않는다.</b> FastAPI 가 만드는 422 는 {@code detail} 이 문자열이 아니라
     * 에러 객체 <b>배열</b>이다(계약 §4 갱신 2026-08-02). 그리고 422 는 "우리 요청이 틀렸다"는 뜻이라
     * 사용자에게 보일 것이 없다 — 로그로만 남긴다.
     */
    DraftResponse draft(PlanSnapshot snapshot, List<UUID> tasksToPlace) {
        try {
            DraftResponse body = restClient.post()
                    .uri("/plans/draft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DraftRequest(snapshot, tasksToPlace))
                    .retrieve()
                    .body(DraftResponse.class);

            if (body == null || body.proposedBlocks() == null) {
                throw new AiUnavailableException("응답 본문이 비었다", null);
            }
            // reason 은 계약상 필수·빈 문자열 금지(§4). 없으면 폴백이 아니라 그대로 진행하되 경고만 남긴다 —
            // 배치 자체는 유효하고, 문구 부재로 초안을 통째로 버리는 것이 사용자에게 더 나쁘다.
            if (body.reason() == null || body.reason().isBlank()) {
                log.warn("AI 초안에 reason 이 없다 — 계약 §4 위반. 배치는 사용한다");
            }
            if (body.meta() != null) {
                log.info("AI 초안 수신: model={} latencyMs={} 배치={}건 미배치={}건",
                        body.meta().model(), body.meta().latencyMs(),
                        body.proposedBlocks().size(),
                        body.unplacedTaskIds() == null ? 0 : body.unplacedTaskIds().size());
            }
            return body;
        } catch (AiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 연결 거부·타임아웃·5xx·422 가 모두 여기로 온다. 호출자에게는 구분이 필요 없다 —
            // 계약 §4 의 실패 표가 502·503·504 에 대해 지시하는 일이 전부 "규칙 폴백"으로 같다.
            // 🔴 원인을 메시지에 담는다. 담지 않으면 폴백 로그가 "실패했다" 만 남아 무엇이
            //    틀렸는지 알 수 없다 — 연결 거부인지 422 인지 구분이 안 되면 고칠 수가 없다.
            throw new AiUnavailableException(
                    "AI 초안 호출 실패: " + ex.getClass().getSimpleName() + " — " + ex.getMessage(), ex);
        }
    }

    /** AI 가 답을 못 준 모든 경우. 받는 쪽이 할 일은 하나 — 규칙 폴백. */
    static class AiUnavailableException extends RuntimeException {
        AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
