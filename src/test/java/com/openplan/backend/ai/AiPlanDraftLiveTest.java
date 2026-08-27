package com.openplan.backend.ai;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.TaskFacts;
import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.port.PlanPlacementPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 AI 서비스 왕복 — <b>{@code AI_BASE_URL} 이 있을 때만 돈다</b>({@code AppleCalDavLiveTest} 와 같은 관례).
 *
 * <p><b>왜 따로 두는가.</b> {@link AiPlacementAdapterTest} 는 클라이언트를 대역으로 바꾸고
 * {@link AiContractSerializationTest} 는 직렬화 <b>문자열</b>만 본다. 둘 다 <b>"AI 서비스가 우리 요청을
 * 실제로 받아주는가"</b> 는 증명하지 못한다 — 우리가 만든 JSON 이 그쪽 Pydantic 검증을 통과하는지,
 * 그쪽 응답이 우리 DTO 로 되돌아오는지는 진짜 왕복으로만 알 수 있다. 계약 §3 의 표기 규약
 * (요일 MON · 시각 HH:mm)이 어긋나면 여기서 422 로 드러난다.
 *
 * <h2>실행 방법</h2>
 * <pre>
 * docker run -d -p 18000:8000 --env-file .env openplan-ai:latest
 * set AI_BASE_URL=http://localhost:18000
 * gradlew.bat test --tests *AiPlanDraftLiveTest*
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AI_BASE_URL", matches = ".+")
class AiPlanDraftLiveTest {

    private static final Logger log = LoggerFactory.getLogger(AiPlanDraftLiveTest.class);

    private static final UUID 급한것 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID 중요한것 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID 긴것 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private PlanPlacementPort adapter() {
        AiProperties props = new AiProperties(System.getenv("AI_BASE_URL"), Duration.ofSeconds(30));
        AiConfig config = new AiConfig();
        AiPlanDraftClient client = config.aiPlanDraftClient(config.aiRestClient(props, config.aiObjectMapper()));
        // 폴백이 도는 것을 성공으로 착각하지 않도록, 여기서는 규칙 대신 "불리면 실패" 를 넣는다.
        return new AiPlacementAdapter(client, (s, ids) -> {
            throw new AssertionError("AI 왕복이 실패해 규칙 폴백으로 내려갔다 — 이 테스트의 목적이 아니다");
        });
    }

    private PlanSnapshot snapshot() {
        return new PlanSnapshot(
                LocalDate.of(2026, 8, 3), ZoneId.of("Asia/Seoul"),
                Instant.parse("2026-08-03T00:00:00Z"),
                List.of(),
                // 월 14~16시 고정 — 제안이 이 구간을 피하는지 본다
                List.of(new FixedWindow(UUID.randomUUID(), DayOfWeek.MONDAY,
                        LocalTime.of(14, 0), LocalTime.of(16, 0), null, null)),
                List.of(
                        new AvailabilityWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), true),
                        new AvailabilityWindow(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), true),
                        new AvailabilityWindow(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), true),
                        new AvailabilityWindow(DayOfWeek.THURSDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), true),
                        new AvailabilityWindow(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), true),
                        new AvailabilityWindow(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(14, 0), false),
                        new AvailabilityWindow(DayOfWeek.SUNDAY, LocalTime.of(10, 0), LocalTime.of(14, 0), false)),
                Map.of(
                        중요한것, new TaskFacts(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 6), 120, 1),
                        긴것, new TaskFacts(LocalDate.of(2026, 8, 6), null, null, 180, 2),
                        급한것, new TaskFacts(LocalDate.of(2026, 8, 5), null, null, 90, 1)));
    }

    /** 어댑터를 거치지 않고 클라이언트를 직접 부른다 — 실패하면 원인이 그대로 올라온다(폴백에 가려지지 않게). */
    private AiPlanDraftClient client() {
        AiProperties props = new AiProperties(System.getenv("AI_BASE_URL"), Duration.ofSeconds(30));
        AiConfig config = new AiConfig();
        return config.aiPlanDraftClient(config.aiRestClient(props, config.aiObjectMapper()));
    }

    @Test
    @DisplayName("실서비스 — 요청이 AI 쪽 스키마 검증을 통과한다 (원인이 폴백에 가려지지 않는다)")
    void 요청이_수락된다() {
        List<UUID> 대상 = List.of(중요한것, 긴것, 급한것);

        AiPlanDraftClient.DraftResponse res = client().draft(snapshot(), 대상);

        assertThat(res.proposedBlocks()).isNotNull();
        log.info("원 응답 — 배치 {}건 · reason {}자",
                res.proposedBlocks().size(), res.reason() == null ? 0 : res.reason().length());
    }

    @Test
    @DisplayName("실서비스 — 스냅샷을 보내면 AI 초안이 돌아온다 (계약 §3 표기 규약 통과)")
    void 실왕복() {
        List<UUID> 대상 = List.of(중요한것, 긴것, 급한것);

        PlacementResult r = adapter().propose(snapshot(), 대상);

        // 1. 요청이 그쪽 Pydantic 검증을 통과했다 = 요일 MON·시각 HH:mm 표기가 맞다
        assertThat(r.placements()).isNotEmpty();
        // 2. 응답이 우리 DTO 로 되돌아왔다
        assertThat(r.placements()).allSatisfy(p -> {
            assertThat(p.taskId()).isIn(대상);
            assertThat(p.startAt()).isBefore(p.endAt());
        });
        // 3. AI 는 근거를 반드시 동반한다(계약 §4) — 규칙 경로면 비어 있다
        assertThat(r.hasReason()).as("AI 응답이면 reason 이 있어야 한다").isTrue();

        log.info("실왕복 성공 — 배치 {}건 · 미배치 {}건", r.placements().size(), r.unplacedTaskIds().size());
        r.placements().forEach(p -> log.info("  {} {} → {}",
                p.taskId().toString().substring(0, 8), p.startAt(), p.endAt()));
        log.info("  근거: {}", r.reason());
    }
}
