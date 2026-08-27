package com.openplan.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 계약 §3 표기 규약 박제 — <b>이 테스트가 없으면 배포에서 422 로만 드러난다.</b>
 *
 * <p>AI 서비스는 요일을 {@code Literal["MON",…]}, 시각을 {@code ^([01]\d|2[0-3]):[0-5]\d$} 로
 * <b>엄격히</b> 검증한다. Jackson 기본 직렬화는 각각 {@code "MONDAY"}·{@code "09:00:00"} 이라
 * 둘 다 통과하지 못한다. 그 간극을 {@link AiConfig#aiObjectMapper()} 가 메우고 있고, 여기서 고정한다.
 */
class AiContractSerializationTest {

    private final ObjectMapper mapper = new AiConfig().aiObjectMapper();

    private PlanSnapshot snapshot() {
        UUID taskId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new PlanSnapshot(
                LocalDate.of(2026, 8, 3),
                ZoneId.of("Asia/Seoul"),
                Instant.parse("2026-08-01T09:00:00Z"),
                List.of(),
                List.of(new FixedWindow(UUID.randomUUID(), DayOfWeek.MONDAY,
                        LocalTime.of(14, 0), LocalTime.of(16, 0), null, null)),
                List.of(new AvailabilityWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), true)),
                Map.of());
    }

    @Test
    @DisplayName("요일은 MONDAY 가 아니라 MON 으로 나간다")
    void 요일은_세글자다() throws Exception {
        String json = mapper.writeValueAsString(snapshot());

        assertThat(json).contains("\"weekday\":\"MON\"");
        assertThat(json).doesNotContain("MONDAY");
    }

    @Test
    @DisplayName("TIME 은 초 없이 HH:mm 으로 나간다")
    void 시각은_초가_없다() throws Exception {
        String json = mapper.writeValueAsString(snapshot());

        assertThat(json).contains("\"startTime\":\"09:00\"");   // 가용창
        assertThat(json).contains("\"endTime\":\"18:00\"");
        assertThat(json).contains("\"startTime\":\"14:00\"");   // 고정일정
        // 초가 붙으면 AI 쪽 정규식(^([01]\\d|2[0-3]):[0-5]\\d$)에 걸린다. TIME 필드만 본다 —
        // referenceTime 은 Instant 라 "09:00:00Z" 를 정상적으로 포함한다(전역 검사를 하면 여기 걸린다).
        assertThat(json).doesNotContain("\"startTime\":\"09:00:00\"");
        assertThat(json).doesNotContain("\"endTime\":\"18:00:00\"");
    }

    @Test
    @DisplayName("Instant 는 UTC(Z)로, 날짜는 YYYY-MM-DD 로 나간다")
    void 시각과_날짜_표기() throws Exception {
        String json = mapper.writeValueAsString(snapshot());

        // AI 쪽 InstantUTC 가 오프셋 0 이 아니면 거부한다 — 숫자 타임스탬프도 거부다.
        assertThat(json).contains("\"referenceTime\":\"2026-08-01T09:00:00Z\"");
        assertThat(json).contains("\"weekStartDate\":\"2026-08-03\"");
        assertThat(json).contains("\"zone\":\"Asia/Seoul\"");
    }

    @Test
    @DisplayName("응답에 모르는 필드(meta 확장 등)가 와도 깨지지 않는다")
    void 미지의_필드를_견딘다() throws Exception {
        String body = """
                {"proposedBlocks":[],"unplacedTaskIds":[],"reason":"근거",
                 "meta":{"model":"m","latencyMs":10,"futureField":"x"},"topLevelNew":1}""";

        AiPlanDraftClient.DraftResponse parsed =
                mapper.readValue(body, AiPlanDraftClient.DraftResponse.class);

        assertThat(parsed.reason()).isEqualTo("근거");
        assertThat(parsed.meta().model()).isEqualTo("m");
    }
}
