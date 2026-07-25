package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.RuleId;
import com.openplan.backend.rule.model.Severity;
import com.openplan.backend.rule.model.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증 엔진 V3(가용시간 초과) 최소 골든 — 김에스더가 JSON 골든 하네스(ST-B3-10)로 확장 예정.
 */
class PlanValidationEngineTest {

    private final PlanValidationEngine engine = new PlanValidationEngine();

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27); // 월요일
    private static final Instant REF = Instant.parse("2026-07-27T00:00:00Z");

    private BlockView blockOnMonday(int startHour, int minutes) {
        return blockOnDay(0, startHour, minutes);
    }

    /** 주 시작(월요일)로부터 {@code plusDays} 일째에 배치된 블록. */
    private BlockView blockOnDay(int plusDays, int startHour, int minutes) {
        Instant start = MONDAY.plusDays(plusDays).atTime(startHour, 0).atZone(ZONE).toInstant();
        Instant end = start.plus(Duration.ofMinutes(minutes));
        return new BlockView(UUID.randomUUID(), BlockType.TASK, UUID.randomUUID(), null, start, end);
    }

    private AvailabilityWindow mondayAvail(int startHour, int endHour) {
        return avail(DayOfWeek.MONDAY, startHour, endHour);
    }

    private AvailabilityWindow avail(DayOfWeek weekday, int startHour, int endHour) {
        return new AvailabilityWindow(weekday, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0), true);
    }

    private PlanSnapshot snapshot(List<BlockView> blocks, List<AvailabilityWindow> avails) {
        return new PlanSnapshot(MONDAY, ZONE, REF, blocks, List.of(), avails, Map.of());
    }

    @Test
    @DisplayName("가용 60분인데 90분 배치 → V3 경고 1건, 저장 가능(차단 0)")
    void 가용시간_초과하면_V3_경고() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10))));

        assertEquals(1, r.issues().size());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(0).ruleId());
        assertEquals(Severity.WARNING, r.issues().get(0).severity());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
        assertTrue(r.savable(), "경고만 있으면 저장 가능");
    }

    @Test
    @DisplayName("가용 120분에 90분 배치 → 위반 없음")
    void 가용시간_이내면_무위반() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 11))));

        assertTrue(r.issues().isEmpty());
        assertTrue(r.savable());
    }

    @Test
    @DisplayName("사유 문구 정본 고정 — 한글 요일 + 가용/배치/초과 수치 (계약 §3.3)")
    void 사유_문구_정본_고정() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10))));

        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 60분 / 배치 90분, 30분 초과)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("초과 요일 2개 → weekday 오름차순(월→수) 고정, 입력 순서 무관 (전순서 4번째 키)")
    void 초과_요일_복수면_요일_오름차순_고정() {
        // 수요일 블록을 먼저 넣어도 출력은 월 → 수 여야 한다 (planBlockId 가 null 이라 weekday 가 유일한 전순서 키)
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnDay(2, 9, 90), blockOnDay(0, 9, 90)),
                List.of(avail(DayOfWeek.WEDNESDAY, 9, 10), mondayAvail(9, 10))));

        assertEquals(2, r.issues().size());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
        assertEquals(DayOfWeek.WEDNESDAY, r.issues().get(1).weekday());
    }

    @Test
    @DisplayName("동일 입력 → 동일 판정 (P1 결정성)")
    void 동일_입력_동일_판정() {
        PlanSnapshot snap = snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10)));
        assertEquals(engine.validate(snap).issues(), engine.validate(snap).issues());
    }
}
