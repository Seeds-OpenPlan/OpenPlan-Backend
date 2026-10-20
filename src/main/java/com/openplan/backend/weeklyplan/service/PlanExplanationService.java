package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.ai.AiPlanExplainClient;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.weeklyplan.dto.PlanExplanationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 계획 설명 생성 — 현재 블록 + 영속된 검증 이슈를 AI 설명 접합({@code POST /plans/explain})에 넘기고
 * 사람이 읽을 한국어 설명을 반환한다.
 *
 * <p><b>대체 경로가 없다.</b> {@link AutoPlacementService}(초안)는 AI 가 죽어도 규칙 first-fit 으로
 * 내려가지만, "이미 내려진 판정을 사람 말로 풀어 설명"하는 일은 규칙 엔진이 지어낼 수 없는 자연어
 * 생성이다 — 그래서 여기는 실패를 숨기지 않고 {@link ErrorCode#E_PLAN_005}(503)로 그대로 올린다.
 *
 * <p>{@code op.ai.base-url}이 비어 있으면 {@link com.openplan.backend.ai.AiConfig}가 접합 빈을
 * 통째로 안 올리므로({@code AiConfig} 클래스 주석), {@link AiPlanExplainClient}를 {@code Optional}로
 * 받는다 — "설정 안 됨"과 "불렀는데 실패함"을 하나의 503으로 합쳐 처리할 수 있어 호출자가 늘어도
 * 분기가 늘지 않는다.
 *
 * <p>이 클래스엔 {@code @Transactional}을 달지 않는다 — 읽기 전용 tx는 {@link PlanExplanationSnapshotReader}
 * 가 스냅샷을 조립하는 동안만 열려 있고, AI 호출(최대 20초 동기 HTTP)은 그 tx가 끝난 뒤 여기서 부른다
 * ({@link AutoPlacementService}와 같은 이유 — 커넥션을 쥔 채 네트워크를 기다리면 동시 요청 수만큼
 * DB 커넥션 풀이 고갈될 수 있다).
 */
@Service
public class PlanExplanationService {

    private static final Logger log = LoggerFactory.getLogger(PlanExplanationService.class);

    private final PlanExplanationSnapshotReader snapshotReader;
    private final Optional<AiPlanExplainClient> explainClient;

    public PlanExplanationService(PlanExplanationSnapshotReader snapshotReader,
                                  Optional<AiPlanExplainClient> explainClient) {
        this.snapshotReader = snapshotReader;
        this.explainClient = explainClient;
    }

    /**
     * 설명 생성. 계획 부재·타인 → 404(스냅샷 조립 단계에서 던져진다). 설명 접합 미설정·호출 실패/타임아웃
     * → 503 {@link ErrorCode#E_PLAN_005}. 가짜 설명을 지어내 돌려주지 않는다(값 창작 금지).
     */
    public PlanExplanationResponse explain(UUID userId, UUID planId) {
        PlanExplanationSnapshotReader.ExplanationInput input = snapshotReader.read(userId, planId);

        AiPlanExplainClient client = explainClient.orElseThrow(() -> {
            log.warn("계획 설명 요청 — 설명 접합 미설정(op.ai.base-url 없음), planId={}", planId);
            return new OpenPlanException(ErrorCode.E_PLAN_005);
        });

        try {
            AiPlanExplainClient.ExplainResponse response = client.explain(input.snapshot(), input.issues());
            String model = response.meta() == null ? null : response.meta().model();
            return new PlanExplanationResponse(response.explanation(), model);
        } catch (AiPlanExplainClient.AiUnavailableException ex) {
            // 초안의 AiPlacementAdapter와 같은 로깅 관례: 요약은 warn, 스택은 debug에만
            // (실패가 운영 로그를 매번 채우지 않게 — 다만 여기는 폴백이 없으므로 사용자에게도 503이 간다).
            log.warn("계획 설명 생성 실패 — 503으로 알린다: {}", ex.getMessage());
            log.debug("계획 설명 생성 실패 상세", ex);
            throw new OpenPlanException(ErrorCode.E_PLAN_005);
        }
    }
}
