package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.model.ReplanOptionResult;
import com.openplan.backend.rule.model.ReplanStrategy;
import com.openplan.backend.rule.model.TaskFacts;
import com.openplan.backend.rule.port.PlanReplanPort;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 재계획 엔진 (SS-07~09 / RB-PLAN-03·04·05) — 3전략. 슬롯 계산·first-fit은 {@link SlotPlanner} 공유.
 *
 * <p>C-1(결정성): Spring·JPA·Clock·Random 금지, 시각은 snapshot만, 같은 입력 → 같은 출력(P1).
 *
 * <p><b>재배치 대상은 TASK 블록만</b>이다. SCHEDULE 블록·고정일정은 시각 고정이라 busy 제약으로만 쓴다.
 * 기준선(KEEP_CURRENT)은 현재 그대로라 생성하지 않는다(라우트가 응답에 별도로 싣는다).
 */
public final class ReplanEngine implements PlanReplanPort {

    @Override
    public List<ReplanOptionResult> generate(PlanSnapshot snapshot) {
        List<MovableBlock> movable = movableBlocks(snapshot);
        List<SlotPlanner.Interval> scheduleBusy = scheduleBusy(snapshot);

        return List.of(
                minimalChange(snapshot, movable, scheduleBusy),
                deadlineFirst(snapshot, movable, scheduleBusy),
                workloadBalance(snapshot, movable, scheduleBusy));
    }

    // ─────────────────────────────────────────── MINIMAL_CHANGE (RB-PLAN-03)

    /**
     * 최소 변경안 — 충돌(다른 블록·고정일정과 겹침) TASK 블록만 이동, 나머지는 그대로.
     * 유지 블록은 busy로 넣어 그 자리를 보존하고, 충돌 블록만 남은 빈 슬롯에 first-fit.
     */
    private ReplanOptionResult minimalChange(PlanSnapshot s, List<MovableBlock> movable,
                                             List<SlotPlanner.Interval> scheduleBusy) {
        List<FixedInterval> fixedIv = fixedIntervals(s);

        List<MovableBlock> kept = new ArrayList<>();
        List<MovableBlock> conflicting = new ArrayList<>();
        for (MovableBlock m : movable) {
            if (isConflicting(m, movable, scheduleBusy, fixedIv)) {
                conflicting.add(m);
            } else {
                kept.add(m);
            }
        }

        // 유지 블록도 busy에 포함(그 자리 보존) + SCHEDULE busy.
        List<SlotPlanner.Interval> busy = new ArrayList<>(scheduleBusy);
        for (MovableBlock k : kept) {
            busy.add(new SlotPlanner.Interval(k.start(), k.end()));
        }
        List<SlotPlanner.FreeSlot> slots = SlotPlanner.freeSlots(
                s.availabilities(), busy, s.activeFixedSchedules(), s.weekStartDate(), s.zone());

        // 충돌 블록은 결정적 순서(시작시각 → blockId)로 재배치.
        conflicting.sort(Comparator.comparing(MovableBlock::start).thenComparing(MovableBlock::blockId));
        SlotPlanner.Result moved = SlotPlanner.firstFit(toPlaceables(conflicting), slots);

        // 유지 블록은 원위치 그대로 제안에 포함.
        List<ProposedPlacement> placements = new ArrayList<>();
        for (MovableBlock k : kept) {
            placements.add(new ProposedPlacement(k.taskId(), k.start(), k.end()));
        }
        placements.addAll(moved.placements());

        return result(ReplanStrategy.MINIMAL_CHANGE, movable, placements, moved.unplaced());
    }

    // ─────────────────────────────────────────── DEADLINE_FIRST (RB-PLAN-04)

    /** 마감 우선안 — 전 TASK를 걷어내고 마감일 순(없음 최후 → taskId) 정렬해 앞 슬롯부터 first-fit. */
    private ReplanOptionResult deadlineFirst(PlanSnapshot s, List<MovableBlock> movable,
                                             List<SlotPlanner.Interval> scheduleBusy) {
        List<SlotPlanner.FreeSlot> slots = SlotPlanner.freeSlots(
                s.availabilities(), scheduleBusy, s.activeFixedSchedules(), s.weekStartDate(), s.zone());

        List<MovableBlock> ordered = new ArrayList<>(movable);
        ordered.sort(Comparator
                .comparing((MovableBlock m) -> m.facts().dueDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MovableBlock::taskId)
                .thenComparing(MovableBlock::blockId));

        SlotPlanner.Result r = SlotPlanner.firstFit(toPlaceables(ordered), slots);
        return result(ReplanStrategy.DEADLINE_FIRST, movable, r.placements(), r.unplaced());
    }

    // ─────────────────────────────────────────── WORKLOAD_BALANCE (RB-PLAN-05)

    /**
     * 부하 분산안 — 요일별 배치량이 가용 총량을 초과한 날의 TASK를 여유 있는 날로 이동.
     * 초과일에서 큰 블록부터 옮겨 그 날 배치량을 가용 이하로 낮추고, 나머지 날 블록은 유지(그 자리 busy).
     */
    private ReplanOptionResult workloadBalance(PlanSnapshot s, List<MovableBlock> movable,
                                               List<SlotPlanner.Interval> scheduleBusy) {
        Map<DayOfWeek, Long> capacity = capacityPerDay(s);
        Map<DayOfWeek, List<MovableBlock>> byDay = new EnumMap<>(DayOfWeek.class);
        for (MovableBlock m : movable) {
            byDay.computeIfAbsent(m.weekday(s.zone()), d -> new ArrayList<>()).add(m);
        }

        Set<DayOfWeek> overDays = new HashSet<>();
        List<MovableBlock> kept = new ArrayList<>();
        List<MovableBlock> toMove = new ArrayList<>();
        for (Map.Entry<DayOfWeek, List<MovableBlock>> e : byDay.entrySet()) {
            long cap = capacity.getOrDefault(e.getKey(), 0L);
            long dayTotal = e.getValue().stream().mapToLong(MovableBlock::minutes).sum();
            if (dayTotal <= cap) {
                kept.addAll(e.getValue()); // 초과 아님 → 그 날 블록 유지
                continue;
            }
            overDays.add(e.getKey());
            // 초과일 — 큰 블록부터 옮겨 초과분을 줄인다(결정적: 분 내림 → blockId).
            List<MovableBlock> dayBlocks = new ArrayList<>(e.getValue());
            dayBlocks.sort(Comparator.comparingLong(MovableBlock::minutes).reversed()
                    .thenComparing(MovableBlock::blockId));
            long placed = dayTotal;
            for (MovableBlock m : dayBlocks) {
                if (placed > cap) {
                    toMove.add(m);
                    placed -= m.minutes();
                } else {
                    kept.add(m);
                }
            }
        }

        // 이동 블록은 <b>초과일이 아닌 날</b>로만 분산한다 — 초과일 가용을 빼지 않으면 옮긴 블록이 도로 그 날로 돌아간다.
        List<AvailabilityWindow> availForMove = s.availabilities().stream()
                .filter(a -> !overDays.contains(a.weekday()))
                .toList();
        List<SlotPlanner.Interval> busy = new ArrayList<>(scheduleBusy);
        for (MovableBlock k : kept) {
            busy.add(new SlotPlanner.Interval(k.start(), k.end()));
        }
        List<SlotPlanner.FreeSlot> slots = SlotPlanner.freeSlots(
                availForMove, busy, s.activeFixedSchedules(), s.weekStartDate(), s.zone());

        toMove.sort(Comparator.comparingLong(MovableBlock::minutes).reversed().thenComparing(MovableBlock::blockId));
        SlotPlanner.Result moved = SlotPlanner.firstFit(toPlaceables(toMove), slots);

        List<ProposedPlacement> placements = new ArrayList<>();
        for (MovableBlock k : kept) {
            placements.add(new ProposedPlacement(k.taskId(), k.start(), k.end()));
        }
        placements.addAll(moved.placements());
        return result(ReplanStrategy.WORKLOAD_BALANCE, movable, placements, moved.unplaced());
    }

    // ─────────────────────────────────────────── 공통 헬퍼

    /** 결과 조립 — changedTaskIds = 원래 (taskId, 시작시각) 조합에 없던 배치의 taskId. */
    private ReplanOptionResult result(ReplanStrategy strategy, List<MovableBlock> original,
                                      List<ProposedPlacement> placements, List<UUID> unplaced) {
        Set<String> originalKeys = new HashSet<>();
        for (MovableBlock m : original) {
            originalKeys.add(m.taskId() + "@" + m.start());
        }
        List<UUID> changed = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ProposedPlacement p : placements) {
            if (!originalKeys.contains(p.taskId() + "@" + p.startAt()) && seen.add(p.taskId())) {
                changed.add(p.taskId());
            }
        }
        return new ReplanOptionResult(strategy, List.copyOf(placements), List.copyOf(unplaced), List.copyOf(changed));
    }

    private List<MovableBlock> movableBlocks(PlanSnapshot s) {
        List<MovableBlock> list = new ArrayList<>();
        for (BlockView b : s.blocks()) {
            if (b.type() == BlockType.TASK && b.taskId() != null) {
                TaskFacts facts = s.taskFacts().get(b.taskId());
                list.add(new MovableBlock(b.blockId(), b.taskId(), b.startAt(), b.endAt(),
                        facts == null ? new TaskFacts(null, null, null, 0, 0) : facts));
            }
        }
        return list;
    }

    private List<SlotPlanner.Interval> scheduleBusy(PlanSnapshot s) {
        List<SlotPlanner.Interval> busy = new ArrayList<>();
        for (BlockView b : s.blocks()) {
            if (b.type() == BlockType.SCHEDULE) {
                busy.add(new SlotPlanner.Interval(b.startAt(), b.endAt()));
            }
        }
        return busy;
    }

    private List<FixedInterval> fixedIntervals(PlanSnapshot s) {
        List<FixedInterval> list = new ArrayList<>();
        for (FixedWindow w : s.activeFixedSchedules()) {
            LocalDate date = SlotPlanner.dateInWeek(s.weekStartDate(), w.weekday());
            list.add(new FixedInterval(
                    date.atTime(w.startTime()).atZone(s.zone()).toInstant(),
                    date.atTime(w.endTime()).atZone(s.zone()).toInstant()));
        }
        return list;
    }

    /** 충돌 = 다른 TASK 블록·SCHEDULE 블록·고정일정과 시간 구간이 겹침(인접은 비겹침). */
    private boolean isConflicting(MovableBlock m, List<MovableBlock> all,
                                  List<SlotPlanner.Interval> scheduleBusy, List<FixedInterval> fixedIv) {
        for (MovableBlock other : all) {
            if (!other.blockId().equals(m.blockId()) && overlaps(m.start(), m.end(), other.start(), other.end())) {
                return true;
            }
        }
        for (SlotPlanner.Interval sb : scheduleBusy) {
            if (overlaps(m.start(), m.end(), sb.start(), sb.end())) {
                return true;
            }
        }
        for (FixedInterval f : fixedIv) {
            if (overlaps(m.start(), m.end(), f.start(), f.end())) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private Map<DayOfWeek, Long> capacityPerDay(PlanSnapshot s) {
        Map<DayOfWeek, Long> cap = new EnumMap<>(DayOfWeek.class);
        s.availabilities().stream().filter(a -> a.active()).forEach(a ->
                cap.merge(a.weekday(), Duration.between(a.startTime(), a.endTime()).toMinutes(), Long::sum));
        return cap;
    }

    private List<SlotPlanner.Placeable> toPlaceables(List<MovableBlock> blocks) {
        return blocks.stream().map(b -> new SlotPlanner.Placeable(b.taskId(), b.minutes())).toList();
    }

    /** 재배치 대상 TASK 블록 — 현재 위치·길이·사실을 함께 안다. */
    private record MovableBlock(UUID blockId, UUID taskId, Instant start, Instant end, TaskFacts facts) {
        long minutes() {
            return Duration.between(start, end).toMinutes();
        }

        DayOfWeek weekday(ZoneId zone) {
            return start.atZone(zone).getDayOfWeek();
        }
    }

    private record FixedInterval(Instant start, Instant end) {
    }
}
