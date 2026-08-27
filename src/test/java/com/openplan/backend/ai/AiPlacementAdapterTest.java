package com.openplan.backend.ai;

import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.port.PlanPlacementPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 어댑터 — 계약 §4 "폴백이 계약의 일부다" 를 박제한다.
 *
 * <p>여기서 지키는 것은 둘이다: ⑴ AI 가 죽어도 사용자는 초안을 받는다 ⑵ AI 가 계약 밖 응답을 줘도
 * 요청하지 않은 태스크가 옮겨지지 않는다.
 */
class AiPlacementAdapterTest {

    private static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID 남의것 = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Instant 시작 = Instant.parse("2026-08-03T00:00:00Z");
    private static final Instant 종료 = Instant.parse("2026-08-03T01:00:00Z");

    private final PlanSnapshot snapshot = new PlanSnapshot(
            LocalDate.of(2026, 8, 3), ZoneId.of("Asia/Seoul"), 시작,
            List.of(), List.of(), List.of(), Map.of());

    /** 규칙 폴백 대역 — 몇 번 불렸는지 센다. */
    private static final class 규칙대역 implements PlanPlacementPort {
        final AtomicInteger 호출 = new AtomicInteger();

        @Override
        public PlacementResult propose(PlanSnapshot s, List<UUID> ids) {
            호출.incrementAndGet();
            return new PlacementResult(List.of(new ProposedPlacement(T1, 시작, 종료)), List.of());
        }
    }

    /** AI 클라이언트 대역 — 응답을 주거나 예외를 던진다. */
    private static AiPlanDraftClient 대역(AiPlanDraftClient.DraftResponse 응답, RuntimeException 예외) {
        return new AiPlanDraftClient(null) {
            @Override
            AiPlanDraftClient.DraftResponse draft(PlanSnapshot s, List<UUID> ids) {
                if (예외 != null) {
                    throw 예외;
                }
                return 응답;
            }
        };
    }

    private static AiPlanDraftClient.DraftResponse.ProposedBlock 블록(UUID taskId, Instant s, Instant e) {
        return new AiPlanDraftClient.DraftResponse.ProposedBlock("TASK", taskId, null, s, e);
    }

    @Test
    @DisplayName("AI 가 답하면 그 제안과 근거 문구를 그대로 올린다")
    void AI_성공() {
        규칙대역 규칙 = new 규칙대역();
        var 응답 = new AiPlanDraftClient.DraftResponse(
                List.of(블록(T1, 시작, 종료)), List.of(T2), "마감이 가까운 것을 앞에 뒀습니다", null);

        PlacementResult r = new AiPlacementAdapter(대역(응답, null), 규칙).propose(snapshot, List.of(T1, T2));

        assertThat(r.placements()).hasSize(1);
        assertThat(r.unplacedTaskIds()).containsExactly(T2);
        assertThat(r.reason()).isEqualTo("마감이 가까운 것을 앞에 뒀습니다");
        assertThat(규칙.호출).hasValue(0); // AI 가 답했으면 규칙은 안 돈다
    }

    @Test
    @DisplayName("AI 가 죽으면 규칙 first-fit 으로 내려간다 — 사용자는 초안을 받는다")
    void AI_실패시_폴백() {
        규칙대역 규칙 = new 규칙대역();
        var 예외 = new AiPlanDraftClient.AiUnavailableException("503", null);

        PlacementResult r = new AiPlacementAdapter(대역(null, 예외), 규칙).propose(snapshot, List.of(T1));

        assertThat(규칙.호출).hasValue(1);
        assertThat(r.placements()).hasSize(1);
        assertThat(r.hasReason()).isFalse(); // 규칙 경로는 문구를 비워 온다 — 라우트가 카탈로그 문구를 넣는다
    }

    @Test
    @DisplayName("요청하지 않은 태스크를 AI 가 배치해 보내면 버린다")
    void 요청_밖_태스크는_버린다() {
        var 응답 = new AiPlanDraftClient.DraftResponse(
                List.of(블록(T1, 시작, 종료), 블록(남의것, 시작, 종료)), List.of(), "근거", null);

        PlacementResult r = new AiPlacementAdapter(대역(응답, null), new 규칙대역()).propose(snapshot, List.of(T1));

        assertThat(r.placements()).extracting(ProposedPlacement::taskId).containsExactly(T1);
    }

    @Test
    @DisplayName("SCHEDULE 블록과 시각이 뒤집힌 제안은 버리고, 그 태스크는 미배치로 돌린다")
    void 계약_밖_제안은_버린다() {
        var schedule = new AiPlanDraftClient.DraftResponse.ProposedBlock("SCHEDULE", T1, null, 시작, 종료);
        var 역전 = 블록(T2, 종료, 시작); // start > end
        var 응답 = new AiPlanDraftClient.DraftResponse(List.of(schedule, 역전), List.of(), "근거", null);

        PlacementResult r = new AiPlacementAdapter(대역(응답, null), new 규칙대역()).propose(snapshot, List.of(T1, T2));

        assertThat(r.placements()).isEmpty();
        // AI 의 unplacedTaskIds 는 비어 있었지만, 실제 배치에서 역산하므로 둘 다 미배치로 잡힌다
        assertThat(r.unplacedTaskIds()).containsExactlyInAnyOrder(T1, T2);
    }

    @Test
    @DisplayName("AI 응답 배열에 null 원소가 섞여도 NPE 없이 나머지 유효한 제안은 살아남는다")
    void 배열_원소가_null이어도_500이_나지_않는다() {
        // Jackson 은 JSON 배열의 null 원소를 그대로 역직렬화하므로, 리스트 안에 null 이 섞여 올 수 있다.
        List<AiPlanDraftClient.DraftResponse.ProposedBlock> 원소null섞임 = new java.util.ArrayList<>();
        원소null섞임.add(블록(T1, 시작, 종료));
        원소null섞임.add(null);
        var 응답 = new AiPlanDraftClient.DraftResponse(원소null섞임, List.of(), "근거", null);

        PlacementResult r = new AiPlacementAdapter(대역(응답, null), new 규칙대역()).propose(snapshot, List.of(T1, T2));

        // null 원소는 계약 밖 제안과 동일하게 조용히 버려지고, 나머지 유효한 T1 제안은 그대로 살아남는다.
        assertThat(r.placements()).extracting(ProposedPlacement::taskId).containsExactly(T1);
        assertThat(r.unplacedTaskIds()).containsExactly(T2);
    }

    @Test
    @DisplayName("배치할 태스크가 없으면 AI 를 부르지 않는다 — 빈 요청에 한도를 쓰지 않는다")
    void 빈_요청은_AI를_안_부른다() {
        규칙대역 규칙 = new 규칙대역();
        AiPlanDraftClient 부르면터짐 = new AiPlanDraftClient(null) {
            @Override
            AiPlanDraftClient.DraftResponse draft(PlanSnapshot s, List<UUID> ids) {
                throw new AssertionError("배치 대상이 없는데 AI 를 불렀다");
            }
        };

        new AiPlacementAdapter(부르면터짐, 규칙).propose(snapshot, List.of());

        assertThat(규칙.호출).hasValue(1);
    }
}
