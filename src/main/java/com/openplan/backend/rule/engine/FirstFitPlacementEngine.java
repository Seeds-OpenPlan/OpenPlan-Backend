package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.model.TaskFacts;
import com.openplan.backend.rule.port.PlanPlacementPort;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * <p><b>알고리즘</b>:
 * <ol>
 *   <li><b>정렬</b>: 우선순위(1 먼저) → 마감일(임박 먼저) → 예상시간(짧은 것 먼저) → taskId(최종 tie-breaker).
 *       null(우선순위·마감일)은 최후로 민다.</li>
 *   <li><b>빈 슬롯</b>: 요일별 가용창(active)에서 기존 블록·고정일정 구간을 빼고 남은 조각. 요일→시작시각 순.</li>
 *   <li><b>배치</b>: 정렬된 태스크를 앞에서부터 훑어 들어가는 첫 슬롯 맨 앞에 배치(first-fit), 슬롯을 그만큼 줄인다.
 *       예상시간이 없거나(null) 어느 슬롯에도 안 들어가면 unplaced.</li>
 * </ol>
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
        List<ProposedPlacement> placements = new ArrayList<>();
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

        List<FreeSlot> slots = buildFreeSlots(snapshot);

        for (Candidate c : candidates) {
            long needed = c.facts().estimatedMinutes();
            boolean placed = false;
            for (FreeSlot slot : slots) {
                if (slot.minutes() >= needed) {
                    Instant start = slot.start;
                    Instant end = start.plus(Duration.ofMinutes(needed));
                    placements.add(new ProposedPlacement(c.taskId(), start, end));
                    slot.start = end; // 슬롯 앞을 소비 — 남은 뒤쪽은 다음 태스크가 쓴다
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                unplaced.add(c.taskId());
            }
        }

        return new PlacementResult(List.copyOf(placements), List.copyOf(unplaced));
    }

    /**
     * 빈 슬롯 = 요일별 가용창(active) − 기존 블록 − 고정일정. 요일(월→일)·시작시각 순으로 정렬해
     * first-fit의 "앞에서부터"를 결정적으로 만든다.
     */
    private List<FreeSlot> buildFreeSlots(PlanSnapshot s) {
        ZoneId zone = s.zone();

        // 그 주에서 이미 점유된 구간(블록 + 고정일정)을 절대 시각으로 모은다.
        List<Interval> busy = new ArrayList<>();
        for (BlockView b : s.blocks()) {
            busy.add(new Interval(b.startAt(), b.endAt()));
        }
        for (FixedWindow w : s.activeFixedSchedules()) {
            LocalDate date = dateInWeek(s.weekStartDate(), w.weekday());
            busy.add(new Interval(
                    date.atTime(w.startTime()).atZone(zone).toInstant(),
                    date.atTime(w.endTime()).atZone(zone).toInstant()));
        }

        List<FreeSlot> slots = new ArrayList<>();
        for (AvailabilityWindow a : s.availabilities()) {
            if (!a.active()) {
                continue;
            }
            LocalDate date = dateInWeek(s.weekStartDate(), a.weekday());
            Instant winStart = date.atTime(a.startTime()).atZone(zone).toInstant();
            Instant winEnd = date.atTime(a.endTime()).atZone(zone).toInstant();
            // 가용창에서 겹치는 busy 구간을 잘라내 남은 조각들을 슬롯으로.
            for (Interval free : subtract(winStart, winEnd, busy)) {
                slots.add(new FreeSlot(free.start(), free.end()));
            }
        }

        slots.sort(Comparator.comparing((FreeSlot f) -> f.start));
        return slots;
    }

    /** [winStart, winEnd)에서 busy 구간들을 빼고 남은 조각. busy는 절대시각(다른 요일 구간은 자연히 안 겹침). */
    private List<Interval> subtract(Instant winStart, Instant winEnd, List<Interval> busy) {
        List<Interval> overlaps = new ArrayList<>();
        for (Interval b : busy) {
            Instant s = max(winStart, b.start());
            Instant e = min(winEnd, b.end());
            if (s.isBefore(e)) {
                overlaps.add(new Interval(s, e));
            }
        }
        overlaps.sort(Comparator.comparing(Interval::start));

        List<Interval> result = new ArrayList<>();
        Instant cursor = winStart;
        for (Interval o : overlaps) {
            if (cursor.isBefore(o.start())) {
                result.add(new Interval(cursor, o.start()));
            }
            if (cursor.isBefore(o.end())) {
                cursor = o.end();
            }
        }
        if (cursor.isBefore(winEnd)) {
            result.add(new Interval(cursor, winEnd));
        }
        return result;
    }

    /** weekStartDate가 속한 주에서 해당 요일의 실제 날짜(주 시작 요일을 월요일로 단정하지 않음 — 엔진 V2와 동일). */
    private static LocalDate dateInWeek(LocalDate weekStartDate, DayOfWeek weekday) {
        int offset = Math.floorMod(weekday.getValue() - weekStartDate.getDayOfWeek().getValue(), 7);
        return weekStartDate.plusDays(offset);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** 정렬 대상 후보(사실 동반). 예상시간 없음은 이 목록에 오기 전 걸러진다. */
    private record Candidate(UUID taskId, TaskFacts facts) {
    }

    /** 불변 구간(절대시각). */
    private record Interval(Instant start, Instant end) {
    }

    /** 가변 빈 슬롯 — 배치할 때마다 {@code start}를 앞으로 밀어 남은 뒤쪽을 다음 태스크에 넘긴다. */
    private static final class FreeSlot {
        private Instant start;
        private final Instant end;

        FreeSlot(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }

        long minutes() {
            return Duration.between(start, end).toMinutes();
        }
    }
}
