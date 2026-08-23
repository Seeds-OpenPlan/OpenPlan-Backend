package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ReplanOptionResult;
import com.openplan.backend.rule.model.ReplanStrategy;
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
 * 재계획 엔진 골든 (SS-07~09) — 3전략(MINIMAL_CHANGE·DEADLINE_FIRST·WORKLOAD_BALANCE).
 * TASK 블록만 재배치, SCHEDULE·고정일정은 제약. 같은 입력 → 같은 출력(P1).
 */
class ReplanEngineTest {

    private final ReplanEngine engine = new ReplanEngine();

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27); // 월요일
    private static final Instant REF = Instant.parse("2026-07-27T00:00:00Z");

    private static final UUID T1 = UUID.fromString("00000001-0000-0000-0000-000000000000");
    private static final UUID T2 = UUID.fromString("00000002-0000-0000-0000-000000000000");
    private static final UUID B1 = UUID.fromString("0000000a-0000-0000-0000-000000000000");
    private static final UUID B2 = UUID.fromString("0000000b-0000-0000-0000-000000000000");

    /** KST 기준 특정 요일·시각 → Instant. day: 0=월. */
    private Instant at(int dayOffset, int hour, int minute) {
        return MONDAY.plusDays(dayOffset).atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private BlockView taskBlock(UUID blockId, UUID taskId, Instant start, Instant end) {
        return new BlockView(blockId, BlockType.TASK, taskId, null, start, end);
    }

    private AvailabilityWindow avail(DayOfWeek d, int fromH, int toH) {
        return new AvailabilityWindow(d, LocalTime.of(fromH, 0), LocalTime.of(toH, 0), true);
    }

    private ReplanOptionResult of(List<ReplanOptionResult> results, ReplanStrategy strategy) {
        return results.stream().filter(r -> r.strategy() == strategy).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("3전략(+기준선 제외) 모두 생성 — MINIMAL·DEADLINE·WORKLOAD")
    void generatesThreeStrategies() {
        PlanSnapshot s = new PlanSnapshot(MONDAY, ZONE, REF, List.of(), List.of(),
                List.of(avail(DayOfWeek.MONDAY, 9, 18)), Map.of());
        List<ReplanOptionResult> results = engine.generate(s);

        assertEquals(3, results.size());
        assertEquals(ReplanStrategy.MINIMAL_CHANGE, results.get(0).strategy());
        assertEquals(ReplanStrategy.DEADLINE_FIRST, results.get(1).strategy());
        assertEquals(ReplanStrategy.WORKLOAD_BALANCE, results.get(2).strategy());
    }

    @Test
    @DisplayName("MINIMAL_CHANGE — 충돌 없는 블록은 그대로, 겹친 블록만 이동")
    void minimalChangeMovesOnlyConflicts() {
        // B1 09:00~10:00, B2 09:30~10:30 (겹침). 가용 월 09~18.
        BlockView b1 = taskBlock(B1, T1, at(0, 9, 0), at(0, 10, 0));
        BlockView b2 = taskBlock(B2, T2, at(0, 9, 30), at(0, 10, 30));
        Map<UUID, TaskFacts> facts = Map.of(
                T1, new TaskFacts(null, null, null, 60, 1),
                T2, new TaskFacts(null, null, null, 60, 2));
        PlanSnapshot s = new PlanSnapshot(MONDAY, ZONE, REF, List.of(b1, b2), List.of(),
                List.of(avail(DayOfWeek.MONDAY, 9, 18)), facts);

        ReplanOptionResult r = of(engine.generate(s), ReplanStrategy.MINIMAL_CHANGE);

        // 둘 다 충돌이므로 둘 다 이동 대상 → 겹침 해소된 새 배치. unplaced 없음.
        assertEquals(2, r.placements().size());
        assertTrue(r.unplacedTaskIds().isEmpty());
        // 재배치 결과는 서로 안 겹쳐야(가용 09~18에 60분 2개는 충분)
        assertTrue(r.changedTaskIds().size() >= 1);
    }

    @Test
    @DisplayName("DEADLINE_FIRST — 마감 임박 태스크가 앞 슬롯에 배치")
    void deadlineFirstPlacesUrgentFirst() {
        // B1(T1) 마감 늦음, B2(T2) 마감 임박. 원래 위치는 T1이 앞(09:00).
        BlockView b1 = taskBlock(B1, T1, at(0, 9, 0), at(0, 10, 0));
        BlockView b2 = taskBlock(B2, T2, at(0, 11, 0), at(0, 12, 0));
        Map<UUID, TaskFacts> facts = Map.of(
                T1, new TaskFacts(LocalDate.of(2026, 8, 10), null, null, 60, 1),
                T2, new TaskFacts(LocalDate.of(2026, 7, 28), null, null, 60, 1)); // 더 임박
        PlanSnapshot s = new PlanSnapshot(MONDAY, ZONE, REF, List.of(b1, b2), List.of(),
                List.of(avail(DayOfWeek.MONDAY, 9, 18)), facts);

        ReplanOptionResult r = of(engine.generate(s), ReplanStrategy.DEADLINE_FIRST);

        // 마감 임박 T2가 첫 슬롯(09:00)에 배치
        assertEquals(T2, r.placements().get(0).taskId());
        assertEquals(at(0, 9, 0), r.placements().get(0).startAt());
        assertEquals(T1, r.placements().get(1).taskId());
    }

    @Test
    @DisplayName("WORKLOAD_BALANCE — 가용 초과일의 블록을 여유일로 이동")
    void workloadBalanceSpreadsOverloadedDay() {
        // 월요일 가용 2시간(09~11)뿐인데 60분 블록 3개(180분) → 초과. 화요일 09~18 여유.
        BlockView b1 = taskBlock(B1, T1, at(0, 9, 0), at(0, 10, 0));
        BlockView b2 = taskBlock(B2, T2, at(0, 10, 0), at(0, 11, 0));
        UUID b3 = UUID.fromString("0000000c-0000-0000-0000-000000000000");
        UUID t3 = UUID.fromString("00000003-0000-0000-0000-000000000000");
        BlockView b3v = taskBlock(b3, t3, at(0, 11, 0), at(0, 12, 0)); // 월 11~12(가용 밖이자 초과분)
        Map<UUID, TaskFacts> facts = Map.of(
                T1, new TaskFacts(null, null, null, 60, 1),
                T2, new TaskFacts(null, null, null, 60, 1),
                t3, new TaskFacts(null, null, null, 60, 1));
        PlanSnapshot s = new PlanSnapshot(MONDAY, ZONE, REF, List.of(b1, b2, b3v), List.of(),
                List.of(avail(DayOfWeek.MONDAY, 9, 11), avail(DayOfWeek.TUESDAY, 9, 18)), facts);

        ReplanOptionResult r = of(engine.generate(s), ReplanStrategy.WORKLOAD_BALANCE);

        // 3개 다 배치되고(월 2개 + 화 1개), 초과분 1개가 옮겨져 changed에 포함
        assertEquals(3, r.placements().size());
        assertTrue(r.unplacedTaskIds().isEmpty());
        assertTrue(r.changedTaskIds().size() >= 1);
    }

    @Test
    @DisplayName("결정성(P1) — 같은 입력이면 같은 결과(두 번 호출)")
    void deterministic() {
        BlockView b1 = taskBlock(B1, T1, at(0, 9, 0), at(0, 10, 0));
        BlockView b2 = taskBlock(B2, T2, at(0, 9, 30), at(0, 10, 30));
        Map<UUID, TaskFacts> facts = Map.of(
                T1, new TaskFacts(null, null, null, 60, 1),
                T2, new TaskFacts(null, null, null, 60, 2));
        PlanSnapshot s = new PlanSnapshot(MONDAY, ZONE, REF, List.of(b1, b2), List.of(),
                List.of(avail(DayOfWeek.MONDAY, 9, 18)), facts);

        assertEquals(engine.generate(s).toString(), engine.generate(s).toString());
    }
}
