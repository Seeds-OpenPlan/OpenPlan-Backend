package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.ProposedPlacement;

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
 * 배치 슬롯 산술 공용 헬퍼 (SS-05·SS-07~09) — 자동 배치({@link FirstFitPlacementEngine})와
 * 재계획({@link ReplanEngine})이 공유한다.
 *
 * <p>순수 유틸(정적 메서드·상태 없음)이라 rule 패키지 순수성(C-1)을 지킨다. 두 엔진의 차이는 "무엇을 busy로
 * 보느냐"(자동배치=모든 블록, 재계획=SCHEDULE 블록만)와 "후보 정렬 기준"뿐이고, <b>빈 슬롯 계산 + first-fit</b>은
 * 동일해 여기로 모은다.
 */
final class SlotPlanner {

    private SlotPlanner() {
    }

    /** 배치 대상 1건 — 이미 정렬·필터링된 상태로 {@link #firstFit}에 넘긴다. */
    record Placeable(UUID taskId, long minutes) {
    }

    /** first-fit 결과 — 배치 제안 + 자리 없어 못 넣은 taskId. */
    record Result(List<ProposedPlacement> placements, List<UUID> unplaced) {
    }

    /** 불변 구간(절대시각). */
    record Interval(Instant start, Instant end) {
    }

    /** 가변 빈 슬롯 — 배치할 때마다 {@code start}를 앞으로 밀어 남은 뒤쪽을 다음 배치가 쓴다. */
    static final class FreeSlot {
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

    /**
     * 빈 슬롯 = 요일별 가용창(active) − busy 구간(블록 + 고정일정). 요일(월→일)·시작시각 순 정렬로
     * first-fit의 "앞에서부터"를 결정적으로 만든다.
     *
     * @param busyBlocks busy로 볼 블록 구간(자동배치=전체 블록, 재계획=SCHEDULE 블록만)
     * @param fixed      해당 주 유효 고정일정(항상 busy)
     */
    static List<FreeSlot> freeSlots(List<AvailabilityWindow> availabilities, List<Interval> busyBlocks,
                                    List<FixedWindow> fixed, LocalDate weekStartDate, ZoneId zone) {
        List<Interval> busy = new ArrayList<>(busyBlocks);
        for (FixedWindow w : fixed) {
            LocalDate date = dateInWeek(weekStartDate, w.weekday());
            busy.add(new Interval(
                    date.atTime(w.startTime()).atZone(zone).toInstant(),
                    date.atTime(w.endTime()).atZone(zone).toInstant()));
        }

        List<FreeSlot> slots = new ArrayList<>();
        for (AvailabilityWindow a : availabilities) {
            if (!a.active()) {
                continue;
            }
            LocalDate date = dateInWeek(weekStartDate, a.weekday());
            Instant winStart = date.atTime(a.startTime()).atZone(zone).toInstant();
            Instant winEnd = date.atTime(a.endTime()).atZone(zone).toInstant();
            for (Interval free : subtract(winStart, winEnd, busy)) {
                slots.add(new FreeSlot(free.start(), free.end()));
            }
        }
        slots.sort(Comparator.comparing((FreeSlot f) -> f.start));
        return slots;
    }

    /**
     * first-fit 배치 — 정렬된 대상을 앞에서부터 훑어 들어가는 첫 슬롯 맨 앞에 배치하고 슬롯을 그만큼 줄인다.
     * 어느 슬롯에도 안 들어가면 unplaced. {@code ordered}는 호출자가 전략별로 정렬·필터링해 넘긴다.
     */
    static Result firstFit(List<Placeable> ordered, List<FreeSlot> slots) {
        List<ProposedPlacement> placements = new ArrayList<>();
        List<UUID> unplaced = new ArrayList<>();

        for (Placeable p : ordered) {
            boolean placed = false;
            for (FreeSlot slot : slots) {
                if (slot.minutes() >= p.minutes()) {
                    Instant start = slot.start;
                    Instant end = start.plus(Duration.ofMinutes(p.minutes()));
                    placements.add(new ProposedPlacement(p.taskId(), start, end));
                    slot.start = end;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                unplaced.add(p.taskId());
            }
        }
        return new Result(List.copyOf(placements), List.copyOf(unplaced));
    }

    /** weekStartDate가 속한 주에서 해당 요일의 실제 날짜(주 시작 요일을 월요일로 단정하지 않음). */
    static LocalDate dateInWeek(LocalDate weekStartDate, DayOfWeek weekday) {
        int offset = Math.floorMod(weekday.getValue() - weekStartDate.getDayOfWeek().getValue(), 7);
        return weekStartDate.plusDays(offset);
    }

    /** [winStart, winEnd)에서 busy 구간들을 빼고 남은 조각. busy는 절대시각(다른 요일 구간은 자연히 안 겹침). */
    private static List<Interval> subtract(Instant winStart, Instant winEnd, List<Interval> busy) {
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

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
