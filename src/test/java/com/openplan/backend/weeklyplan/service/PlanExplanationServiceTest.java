package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.ai.AiPlanExplainClient;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.weeklyplan.dto.PlanExplanationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계획 설명 서비스 — "대체 경로가 없다"(E-PLAN-005)를 박제한다. {@link AiPlacementAdapterTest}(초안)와
 * 대조적으로, 여기선 실패를 숨기지 않고 전부 503으로 올라가는 것이 기대 동작이다.
 *
 * <p>DB를 쓰지 않는다 — {@link PlanExplanationSnapshotReader}는 {@code read}를 오버라이드한 대역으로
 * 바꾸고({@link AiPlacementAdapterTest}의 {@code AiPlanDraftClient} 대역과 같은 기법),
 * {@link AiPlanExplainClient}도 {@code explain}을 오버라이드한 대역을 쓴다.
 */
class PlanExplanationServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAN = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final PlanSnapshot SNAPSHOT = new PlanSnapshot(
            LocalDate.of(2026, 8, 3), ZoneId.of("Asia/Seoul"), Instant.parse("2026-08-03T00:00:00Z"),
            List.of(), List.of(), List.of(), Map.of());

    /** 스냅샷 읽기 대역 — DB 접근 없이 고정 입력을 돌려준다. */
    private static PlanExplanationSnapshotReader 리더대역(List<String> issues) {
        return new PlanExplanationSnapshotReader(null, null, null, null) {
            @Override
            public ExplanationInput read(UUID userId, UUID planId) {
                assertThat(userId).isEqualTo(USER);
                assertThat(planId).isEqualTo(PLAN);
                return new ExplanationInput(SNAPSHOT, issues);
            }
        };
    }

    /** 설명 접합 대역 — 응답을 주거나 예외를 던진다. */
    private static AiPlanExplainClient 설명클라이언트대역(AiPlanExplainClient.ExplainResponse 응답,
                                                      RuntimeException 예외) {
        return new AiPlanExplainClient(null) {
            @Override
            public AiPlanExplainClient.ExplainResponse explain(PlanSnapshot snapshot, List<String> issues) {
                if (예외 != null) {
                    throw 예외;
                }
                return 응답;
            }
        };
    }

    @Test
    @DisplayName("설명 접합이 응답하면 explanation·model을 그대로 올린다")
    void 성공() {
        var 응답 = new AiPlanExplainClient.ExplainResponse(
                "이번 주는 월요일에 집중 배치되어 있습니다.",
                new AiPlanExplainClient.ExplainResponse.Meta("ollama/qwen3:4b-instruct", 1200));
        var service = new PlanExplanationService(
                리더대역(List.of()), Optional.of(설명클라이언트대역(응답, null)));

        PlanExplanationResponse result = service.explain(USER, PLAN);

        assertThat(result.explanation()).isEqualTo("이번 주는 월요일에 집중 배치되어 있습니다.");
        assertThat(result.model()).isEqualTo("ollama/qwen3:4b-instruct");
    }

    @Test
    @DisplayName("설명 접합이 미설정(Optional.empty)이면 503 E-PLAN-005 — 대체 경로가 없다")
    void 설정_안됨() {
        var service = new PlanExplanationService(리더대역(List.of()), Optional.empty());

        assertThatThrownBy(() -> service.explain(USER, PLAN))
                .isInstanceOf(OpenPlanException.class)
                .extracting(ex -> ((OpenPlanException) ex).errorCode())
                .isEqualTo(ErrorCode.E_PLAN_005);
    }

    @Test
    @DisplayName("설명 접합 호출이 실패하면 503 E-PLAN-005 — 가짜 설명을 지어내지 않는다")
    void 호출_실패() {
        var 예외 = new AiPlanExplainClient.AiUnavailableException("504", null);
        var service = new PlanExplanationService(
                리더대역(List.of()), Optional.of(설명클라이언트대역(null, 예외)));

        assertThatThrownBy(() -> service.explain(USER, PLAN))
                .isInstanceOf(OpenPlanException.class)
                .extracting(ex -> ((OpenPlanException) ex).errorCode())
                .isEqualTo(ErrorCode.E_PLAN_005);
    }

    @Test
    @DisplayName("영속된 검증 이슈가 있으면 그대로 설명 접합에 전달한다 — 새 판정을 지어내지 않는다")
    void 이슈를_그대로_전달() {
        List<String> issues = List.of("규칙 1에 의해 겹치는 일정이 있습니다");
        var 캡처용 = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        AiPlanExplainClient client = new AiPlanExplainClient(null) {
            @Override
            public AiPlanExplainClient.ExplainResponse explain(PlanSnapshot snapshot, List<String> received) {
                캡처용.set(received);
                return new AiPlanExplainClient.ExplainResponse("설명", null);
            }
        };
        var service = new PlanExplanationService(리더대역(issues), Optional.of(client));

        service.explain(USER, PLAN);

        assertThat(캡처용.get()).isEqualTo(issues);
    }
}
