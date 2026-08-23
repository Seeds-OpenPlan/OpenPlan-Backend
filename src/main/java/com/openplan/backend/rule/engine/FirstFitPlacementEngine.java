package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.TaskFacts;
import com.openplan.backend.rule.port.PlanPlacementPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 자동 배치 엔진 (SS-05 / RB-PLAN-01) — first-fit.
 *
 * <p>C-1(결정성): 이 패키지에 Spring·JPA·Clock·Random·네트워크 import 금지(RuleEnginePurityTest 강제).
 * 시각은 오직 {@code snapshot.zone()}·{@code snapshot.weekStartDate()}. 같은 입력 → 같은 출력(P1).
 *
 * <p><b>알고리즘</b>: ①정렬(우선순위→마감일→예상시간 짧은것→taskId) ②빈 슬롯 = 가용창 − 전체 블록 − 고정일정
 * ③first-fit. 슬롯 계산·first-fit은 {@link SlotPlanner}(재계획 엔진과 공유). 미배치 대상만 배치하므로 busy에
 * <b>모든 블록</b>을 넣는다(기존 배치를 피한다 — 재계획과 다른 점).
 */
public final class FirstFitPlacementEngine implements PlanPlacementPort {

    /**
     * 후보 정렬 — 결정성(P1)의 핵심. 우선순위 없음·마감일 없음은 최후.
     * 예상시간은 짧은 것 먼저(작은 태스크로 틈새 우선 채움), 마지막 tie-breaker는 taskId(UUID) ASC.
     */
    private static Comparator<Candidate> order() {
        return Comparator
                .comparingInt((Candidate c) -> priorityKey(c.facts().priority()))
                .thenComparing(c -> c.facts().dueDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(c -> c.facts().estimatedMinutes())
                .thenComparing(Candidate::taskId);
    }

    /**
     * 우선순위 정렬 키. 유효값 1(높음)·2·3은 그대로(오름차순 = 높은 것 먼저), <b>미설정(0·≤0)은 최후</b>.
     * {@code TaskFacts.priority}는 int라 조립기가 "없음"을 0으로 매핑한다 — 0을 그대로 두면 1보다 앞서므로 보정한다.
     */
    private static int priorityKey(int priority) {
        return priority >= 1 ? priority : Integer.MAX_VALUE;
    }

    @Override
    public PlacementResult propose(PlanSnapshot snapshot, List<UUID> taskIds) {
        List<UUID> unplaced = new ArrayList<>();

        // 후보 = taskIds 중 사실이 있는 것. 예상시간 없음(null·≤0)은 정렬 전에 unplaced로 분리(길이를 모르면 못 놓음).
        List<Candidate> candidates = new ArrayList<>();
        for (UUID taskId : taskIds) {
            TaskFacts facts = snapshot.taskFacts().get(taskId);
            if (facts == null || facts.estimatedMinutes() <= 0) {
                unplaced.add(taskId);
                continue;
            }
            candidates.add(new Candidate(taskId, facts));
        }
        candidates.sort(order());

        // busy = 모든 기존 블록(자동배치는 기존 배치를 피한다) + 고정일정.
        List<SlotPlanner.Interval> busyBlocks = new ArrayList<>();
        for (BlockView b : snapshot.blocks()) {
            busyBlocks.add(new SlotPlanner.Interval(b.startAt(), b.endAt()));
        }
        List<SlotPlanner.FreeSlot> slots = SlotPlanner.freeSlots(
                snapshot.availabilities(), busyBlocks, snapshot.activeFixedSchedules(),
                snapshot.weekStartDate(), snapshot.zone());

        List<SlotPlanner.Placeable> ordered = candidates.stream()
                .map(c -> new SlotPlanner.Placeable(c.taskId(), c.facts().estimatedMinutes()))
                .toList();
        SlotPlanner.Result result = SlotPlanner.firstFit(ordered, slots);

        unplaced.addAll(result.unplaced());
        return new PlacementResult(result.placements(), List.copyOf(unplaced));
    }

    /** 정렬 대상 후보(사실 동반). 예상시간 없음은 이 목록에 오기 전 걸러진다. */
    private record Candidate(UUID taskId, TaskFacts facts) {
    }
}
