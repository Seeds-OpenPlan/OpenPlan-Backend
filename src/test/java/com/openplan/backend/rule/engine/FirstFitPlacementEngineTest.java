package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.model.TaskFacts;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 자동 배치 엔진 골든 (SS-05 / RB-PLAN-01) — first-fit.
 * 정렬(우선순위→마감일→예상시간→taskId)·틈새 채우기·unplaced·null 예상시간·결정성을 박제한다.
 */
class FirstFitPlacementEngineTest {

    private final FirstFitPlacementEngine engine = new FirstFitPlacementEngine();

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27); // 월요일
    private static final Instant REF = Instant.parse("2026-07-27T00:00:00Z");

    private static final UUID T1 = UUID.fromString("00000001-0000-0000-0000-000000000000");
    private static final UUID T2 = UUID.fromString("00000002-0000-0000-0000-000000000000");
    private static final UUID T3 = UUID.fromString("00000003-0000-0000-0000-000000000000");

    /** 월요일 09:00~12:00 가용창 하나(180분). */
    private PlanSnapshot snapshot(List<BlockView> blocks, Map<UUID, TaskFacts> facts) {
        AvailabilityWindow mon = new AvailabilityWindow(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), true);
        return new PlanSnapshot(MONDAY, ZONE, REF, blocks, List.of(), List.of(mon), facts);
    }

    private TaskFacts facts(Integer priority, LocalDate dueDate, int estimatedMinutes) {
        return new TaskFacts(dueDate, null, null, estimatedMinutes, priority == null ? 0 : priority);
    }

    private Instant monAt(int hour, int minute) {
        return MONDAY.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    @Test
    @DisplayName("빈 슬롯에 순차 배치 — 09:00부터 앞에서 채운다(틈새 채우기)")
    void fillsSlotSequentially() {
        Map<UUID, TaskFacts> facts = Map.of(
                T1, facts(1, null, 60),   // 우선순위 1
                T2, facts(2, null, 60));  // 우선순위 2
        PlacementResult r = engine.propose(snapshot(List.of(), facts), List.of(T1, T2));

        assertEquals(2, r.placements().size());
        assertEquals(0, r.unplacedTaskIds().size());
        // T1(우선순위 높음)이 09:00~10:00, T2가 이어서 10:00~11:00
        assertEquals(new ProposedPlacement(T1, monAt(9, 0), monAt(10, 0)), r.placements().get(0));
        assertEquals(new ProposedPlacement(T2, monAt(10, 0), monAt(11, 0)), r.placements().get(1));
    }

    @Test
    @DisplayName("정렬 — 우선순위 → 마감일 → 예상시간(짧은 것 먼저)")
    void ordering() {
        Map<UUID, TaskFacts> facts = Map.of(
                T1, facts(2, null, 30),                       // 우선순위 2
                T2, facts(1, LocalDate.of(2026, 8, 1), 60),   // 우선순위 1 → 가장 먼저
                T3, facts(1, LocalDate.of(2026, 7, 30), 60)); // 우선순위 1 · 마감 더 임박 → T2보다 먼저
        PlacementResult r = engine.propose(snapshot(List.of(), facts), List.of(T1, T2, T3));

        // 순서: T3(1·7/30) → T2(1·8/1) → T1(2)
        assertEquals(T3, r.placements().get(0).taskId());
        assertEquals(T2, r.placements().get(1).taskId());
        assertEquals(T1, r.placements().get(2).taskId());
    }

    @Test
    @DisplayName("기존 블록 회피 — 점유 구간을 빼고 남은 슬롯에 배치")
    void avoidsExistingBlocks() {
        // 10:00~11:00 이미 점유 → 가용 09~12 중 09~10, 11~12 만 빔
        BlockView busy = new BlockView(UUID.randomUUID(), BlockType.SCHEDULE, null, null,
                monAt(10, 0), monAt(11, 0));
        Map<UUID, TaskFacts> facts = Map.of(T1, facts(1, null, 60));
        PlacementResult r = engine.propose(snapshot(List.of(busy), facts), List.of(T1));

        // 첫 빈 슬롯 09:00~10:00에 배치
        assertEquals(new ProposedPlacement(T1, monAt(9, 0), monAt(10, 0)), r.placements().get(0));
    }

    @Test
    @DisplayName("슬롯 부족 — 가용(180분)보다 큰 태스크는 unplaced")
    void tooLargeIsUnplaced() {
        Map<UUID, TaskFacts> facts = Map.of(T1, facts(1, null, 240)); // 240 > 180
        PlacementResult r = engine.propose(snapshot(List.of(), facts), List.of(T1));

        assertTrue(r.placements().isEmpty());
        assertEquals(List.of(T1), r.unplacedTaskIds());
    }

    @Test
    @DisplayName("예상시간 없음(null) → 배치 시도 없이 unplaced")
    void nullEstimatedIsUnplaced() {
        Map<UUID, TaskFacts> facts = Map.of(T1, new TaskFacts(null, null, null, 0, 1)); // estimated 0
        PlacementResult r = engine.propose(snapshot(List.of(), facts), List.of(T1));

        assertTrue(r.placements().isEmpty());
        assertEquals(List.of(T1), r.unplacedTaskIds());
    }

    @Test
    @DisplayName("일부만 배치 — 앞 태스크가 슬롯을 소진하면 뒤는 unplaced")
    void partialPlacement() {
        Map<UUID, TaskFacts> facts = Map.of(
                T1, facts(1, null, 120),  // 09:00~11:00
                T2, facts(2, null, 120)); // 남은 11:00~12:00(60분)엔 안 들어감
        PlacementResult r = engine.propose(snapshot(List.of(), facts), List.of(T1, T2));

        assertEquals(1, r.placements().size());
        assertEquals(T1, r.placements().get(0).taskId());
        assertEquals(List.of(T2), r.unplacedTaskIds());
    }

    @Test
    @DisplayName("우선순위 없음(0) → 최후 배치 (유효 1·2·3보다 뒤)")
    void unsetPriorityGoesLast() {
        Map<UUID, TaskFacts> facts = Map.of(
                T1, facts(null, null, 60),  // 우선순위 없음(0) → 최후
                T2, facts(3, null, 60));    // 우선순위 3 → 먼저
        PlacementResult r = engine.propose(snapshot(List.of(), facts), List.of(T1, T2));

        assertEquals(T2, r.placements().get(0).taskId()); // 유효 우선순위가 먼저
        assertEquals(T1, r.placements().get(1).taskId()); // 없음이 뒤
    }

    @Test
    @DisplayName("결정성(P1) — 입력 순서가 달라도 같은 결과")
    void deterministic() {
        Map<UUID, TaskFacts> facts = Map.of(
                T1, facts(1, null, 60),
                T2, facts(2, null, 60));
        PlacementResult a = engine.propose(snapshot(List.of(), facts), List.of(T1, T2));
        PlacementResult b = engine.propose(snapshot(List.of(), facts), List.of(T2, T1));

        assertEquals(a.placements(), b.placements());
        assertEquals(a.unplacedTaskIds(), b.unplacedTaskIds());
    }
}
