package com.openplan.backend.validation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openplan.backend.rule.engine.PlanValidationEngine;
import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 규칙 엔진 dry-run 표면의 HTTP 왕복 테스트(standalone MockMvc, DB 불요).
 *
 * <p>엔진 판정 자체는 {@code PlanValidationEngineTest}가 검증한다. 여기서 증명하는 것은
 * <b>스냅샷 JSON → 엔진 → ValidationReport JSON</b> 경로가 실제로 뚫려 있다는 사실뿐이다.
 * 즉 라우팅·역직렬화(Instant·ZoneId·DayOfWeek)·응답 봉투가 맞는지를 본다.
 */
class RuleValidationStubControllerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-07-27 은 월요일. C-1: 시각은 referenceTime 으로만 주입한다. */
    private static final Instant REFERENCE = Instant.parse("2026-07-27T00:00:00Z");
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 27);

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleValidationStubController(new PlanValidationEngine()))
                .setMessageConverters(converter)
                .build();
    }

    @Test
    @DisplayName("가용 초과 → 200 + V3 경고 1건 (검증 위반은 오류가 아니다)")
    void 가용초과시_V3_경고를_돌려준다() throws Exception {
        // 월 09:00~14:00 KST 배치 = 300분 / 월 가용 09:00~11:00 = 120분 → 초과
        PlanSnapshot snapshot = snapshot(
                block("2026-07-27T00:00:00Z", "2026-07-27T05:00:00Z"),
                availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0)));

        mockMvc.perform(post("/validations/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(snapshot)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issues.length()").value(1))
                .andExpect(jsonPath("$.data.issues[0].ruleId").value("V3_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.data.issues[0].severity").value("WARNING"))
                .andExpect(jsonPath("$.data.issues[0].weekday").value("MONDAY"))
                // V3 는 요일 단위 이슈라 blockId·taskId 가 없다(계약 §3.3)
                .andExpect(jsonPath("$.data.issues[0].planBlockId").doesNotExist())
                // 경고만 있으므로 저장은 가능하다(PLAN-28: BLOCK 0건 → savable)
                .andExpect(jsonPath("$.data.savable").value(true))
                // evaluatedAt 은 주입한 referenceTime 그대로여야 한다(C-1 결정성)
                .andExpect(jsonPath("$.data.evaluatedAt").value("2026-07-27T00:00:00Z"));
    }

    @Test
    @DisplayName("가용 이내 배치 → 200 + 이슈 0건")
    void 가용이내면_이슈가_없다() throws Exception {
        // 월 09:00~11:00 KST 배치 = 120분 / 월 가용 09:00~18:00 = 540분
        PlanSnapshot snapshot = snapshot(
                block("2026-07-27T00:00:00Z", "2026-07-27T02:00:00Z"),
                availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)));

        mockMvc.perform(post("/validations/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(snapshot)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issues.length()").value(0))
                .andExpect(jsonPath("$.data.savable").value(true));
    }

    private PlanSnapshot snapshot(BlockView block, AvailabilityWindow availability) {
        return new PlanSnapshot(
                WEEK_START, SEOUL, REFERENCE,
                List.of(block),
                List.of(),            // 고정일정 없음 — 조립이 유효 창만 넘긴다는 전제
                List.of(availability),
                Map.of());            // taskFacts — V3 판정에 불필요
    }

    private BlockView block(String startAt, String endAt) {
        return new BlockView(UUID.randomUUID(), BlockType.TASK, UUID.randomUUID(), null,
                Instant.parse(startAt), Instant.parse(endAt));
    }

    private AvailabilityWindow availability(DayOfWeek weekday, LocalTime start, LocalTime end) {
        return new AvailabilityWindow(weekday, start, end, true);
    }
}
