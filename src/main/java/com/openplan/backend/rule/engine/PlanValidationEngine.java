package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.RuleId;
import com.openplan.backend.rule.model.Severity;
import com.openplan.backend.rule.model.TaskFacts;
import com.openplan.backend.rule.model.ValidationIssue;
import com.openplan.backend.rule.model.ValidationReport;
import com.openplan.backend.rule.port.PlanValidationPort;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 계획 적정성 검증 엔진 (rule-engine-contract §2·§3.3).
 *
 * C-1(결정성): 이 패키지에 Spring·JPA·java.time.Clock·Random·네트워크 import 금지.
 *              시각은 오직 {@code snapshot.referenceTime()}. (RuleEnginePurityTest 로 강제)
 * C-2: 저장소 핸들 없음 — 순수 함수, 자동 확정 불가.
 *
 * 구현 현황: V1~V7 전량. 차단은 V1·V2, 나머지는 경고.
 */
public final class PlanValidationEngine implements PlanValidationPort {

    /**
     * 이슈 전순서(출력 결정성) — 계약 §3.3 확정(2026-07-25) · 개정(2026-08-02, ADR-0013):
     * severity(BLOCK 먼저) → ruleId(enum 선언 순 V1..V7) → planBlockId(UUID ASC, null 최후)
     * → counterpartId(UUID ASC, null 최후) → weekday(월→일, null 최후).
     *
     * weekday 가 키인 이유: V3 는 요일 단위 이슈라 planBlockId 가 null 이고,
     *   그것만으로는 초과 요일이 2개 이상일 때 동률이 생겨 P1(결정성)이 깨진다.
     * counterpartId 가 4번째 키인 이유: 쌍 규칙(V1·V2)은 한 블록이 여러 상대와 겹칠 수 있어
     *   planBlockId 까지의 3키가 동률이 된다(쌍 규칙에서 taskId·weekday 는 null). 상대 둘의 시간
     *   구간이 같으면 reason 까지 문자 단위로 같아져 정렬 이전에 이슈 식별 자체가 불가능해진다.
     *   weekday 앞에 두므로 V3(둘 다 null)의 기존 출력 순서는 변하지 않는다.
     *
     * UUID 비교는 {@link java.util.UUID#compareTo} 자연 순서를 쓴다 — 대표 블록 선정(§3.3
     * "UUID 오름차순 첫 블록")과 반드시 같은 기준이어야 정렬·선정이 어긋나지 않는다.
     */
    /**
     * V7 버퍼 부족 임계값(%) — {@code ASSUMPTION-Q2}, 리드 승인 D-19.
     *
     * <p>정본 US가 값을 침묵해 신설한 가정이다. 요일 가용 시간의 10% 미만이 남으면 "여유 없음"으로 본다 —
     * V3(초과)와 무경고 구간 사이를 메우는 최소 창작이다. <b>주입형이 아니라 상수인 이유</b>는 계약이
     * 문구 정본을 코드 상수로 확정한 것과 같다(C-1: 엔진은 IO를 하지 않는다). 재결정 시 이 값을 바꾸고
     * 골든을 갱신한다 — 의도된 마찰이다.
     */
    private static final int BUFFER_THRESHOLD_PCT = 10;

    private static final Comparator<ValidationIssue> ORDER =
            Comparator.comparingInt((ValidationIssue i) -> i.severity().ordinal())
                    .thenComparingInt(i -> i.ruleId().ordinal())
                    .thenComparing(ValidationIssue::planBlockId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ValidationIssue::counterpartId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ValidationIssue::weekday, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    public ValidationReport validate(PlanSnapshot snapshot) {
        List<ValidationIssue> issues = new ArrayList<>();

        issues.addAll(checkOverlap(snapshot));          // V1 (BLOCK)
        issues.addAll(checkFixedConflict(snapshot));    // V2 (BLOCK)
        issues.addAll(checkCapacityExceeded(snapshot)); // V3 (WARNING)
        issues.addAll(checkOutOfAvailability(snapshot));// V4 (WARNING)
        issues.addAll(checkOutOfWbs(snapshot));         // V5 (WARNING)
        issues.addAll(checkAfterDueDate(snapshot));     // V6 (WARNING)
        issues.addAll(checkBufferShortage(snapshot));   // V7 (WARNING)

        issues.sort(ORDER);
        boolean savable = issues.stream().noneMatch(i -> i.severity() == Severity.BLOCK);
        return new ValidationReport(savable, List.copyOf(issues), snapshot.referenceTime());
    }

    /**
     * V1: 두 블록의 시간 구간이 교차하면 BLOCK. 교차식 {@code a.start < b.end && b.start < a.end}
     * (1분이라도) — 정확히 인접({@code a.end == b.start})은 비겹침이다.
     *
     * <p>이슈 단위는 <b>무순서 쌍당 1건</b>(계약 §3.3 · ADR-0013). 블록 타입 무관(TASK↔SCHEDULE 포함).
     * 3중 상호 겹침은 쌍 3건이 된다 — 규칙 간·건 간 병합 로직을 넣지 않는 편이 결정성 검증에 낫다.
     * 대표 {@code planBlockId} = 쌍 중 UUID 오름차순 첫 블록, {@code counterpartId} = 나머지.
     */
    private List<ValidationIssue> checkOverlap(PlanSnapshot s) {
        List<BlockView> blocks = s.blocks();
        List<ValidationIssue> issues = new ArrayList<>();

        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                BlockView x = blocks.get(i);
                BlockView y = blocks.get(j);
                if (!overlaps(x, y)) continue;

                // 대표 = UUID 오름차순 첫 블록 (입력 순서와 무관하게 결정적)
                boolean xFirst = x.blockId().compareTo(y.blockId()) < 0;
                BlockView base = xFirst ? x : y;
                BlockView counterpart = xFirst ? y : x;

                long overlapMinutes = Duration.between(
                        max(x.startAt(), y.startAt()), min(x.endAt(), y.endAt())).toMinutes();

                ZonedDateTime baseStart = base.startAt().atZone(s.zone());
                ZonedDateTime baseEnd = base.endAt().atZone(s.zone());
                ZonedDateTime cpStart = counterpart.startAt().atZone(s.zone());
                ZonedDateTime cpEnd = counterpart.endAt().atZone(s.zone());

                issues.add(new ValidationIssue(
                        RuleId.V1_OVERLAP, Severity.BLOCK,
                        base.blockId(), counterpart.blockId(),
                        null, null, // 쌍 규칙은 판정 축 필드만 채운다 (계약 §3.3)
                        RuleMessages.v1Overlap(
                                baseStart.getDayOfWeek(), baseStart.toLocalTime(), baseEnd.toLocalTime(),
                                cpStart.getDayOfWeek(), cpStart.toLocalTime(), cpEnd.toLocalTime(),
                                overlapMinutes)));
            }
        }
        return issues;
    }

    /** 계약 §3.3 V1: {@code a.start < b.end && b.start < a.end}. 인접(end == start)은 비겹침. */
    private static boolean overlaps(BlockView a, BlockView b) {
        return a.startAt().isBefore(b.endAt()) && b.startAt().isBefore(a.endAt());
    }

    /**
     * V2: 블록이 <b>해당 주 유효</b> 고정 일정 창과 교차하면 BLOCK (계약 §3.3 V2).
     * 창의 {@code weekday}+시간을 해당 주 날짜로 전개한 뒤 V1과 <b>동일한 교차식</b>을 쓴다 —
     * 인접({@code block.end == fixed.start})은 비겹침.
     *
     * <p>이슈 단위는 <b>(블록, 고정 일정) 쌍당 1건</b>. 대표 {@code planBlockId} = 블록,
     * {@code counterpartId} = {@code fixedScheduleId} (계약 §3.3 "V2는 항상 블록 쪽 — 선정 불요").
     * 한 블록이 여러 창과 겹치면 쌍 수만큼 나오고, 전순서 4번째 키인 {@code counterpartId} 가 가른다.
     *
     * <p><b>주차 예외(PLAN-33)·INACTIVE 는 여기서 보지 않는다</b> — 스냅샷 조립(BE-2) 소관이라
     * 예외된 일정은 애초에 {@code activeFixedSchedules} 에 없다. 즉 <b>미발생 = 그 주 한정 비활성</b>
     * 이라는 의미론이 성립한다(B12 · {@link com.openplan.backend.rule.model.FixedWindow} javadoc).
     * 엔진은 받은 창을 전부 유효로 취급한다.
     *
     * <p>{@code effectiveFrom}/{@code effectiveTo} 는 <b>양쪽 다 null 허용</b>(무기한)이다 —
     * DB {@code fixed_schedules.start_date/end_date} 가 nullable 이고
     * {@code FixedScheduleValidator.validateDates} 가 "한쪽만/둘 다 null 은 허용"으로 못박았다.
     * null 을 열린 구간으로 다루지 않으면 무기한 고정 일정이 통째로 판정에서 빠진다.
     */
    private List<ValidationIssue> checkFixedConflict(PlanSnapshot s) {
        List<ValidationIssue> issues = new ArrayList<>();

        for (BlockView block : s.blocks()) {
            for (FixedWindow window : s.activeFixedSchedules()) {
                LocalDate occurrence = dateInWeek(s.weekStartDate(), window.weekday());
                if (outsideEffectiveRange(window, occurrence)) continue;

                Instant fixedStart = occurrence.atTime(window.startTime()).atZone(s.zone()).toInstant();
                Instant fixedEnd = occurrence.atTime(window.endTime()).atZone(s.zone()).toInstant();
                if (!block.startAt().isBefore(fixedEnd) || !fixedStart.isBefore(block.endAt())) continue;

                long overlapMinutes = Duration.between(
                        max(block.startAt(), fixedStart), min(block.endAt(), fixedEnd)).toMinutes();

                ZonedDateTime blockStart = block.startAt().atZone(s.zone());
                ZonedDateTime blockEnd = block.endAt().atZone(s.zone());

                issues.add(new ValidationIssue(
                        RuleId.V2_FIXED_CONFLICT, Severity.BLOCK,
                        block.blockId(), window.fixedScheduleId(),
                        null, null, // 쌍 규칙은 판정 축 필드만 채운다 (계약 §3.3)
                        RuleMessages.v2FixedConflict(
                                blockStart.getDayOfWeek(), blockStart.toLocalTime(), blockEnd.toLocalTime(),
                                window.weekday(), window.startTime(), window.endTime(),
                                overlapMinutes)));
            }
        }
        return issues;
    }

    /**
     * 주 시작일이 속한 주에서 해당 요일의 실제 날짜. {@code weekStartDate} 가 무슨 요일이든
     * 0~6일 뒤에서 찾는다 — 주 시작 요일을 월요일로 단정하지 않기 위해서다(계약은 주 시작 요일을
     * 고정하지 않고 {@code weekStartDate} 만 준다).
     */
    private static LocalDate dateInWeek(LocalDate weekStartDate, DayOfWeek weekday) {
        int offset = Math.floorMod(weekday.getValue() - weekStartDate.getDayOfWeek().getValue(), 7);
        return weekStartDate.plusDays(offset);
    }

    /** 계약 §3.3 V2 "effectiveFrom/To 범위 밖 제외". null = 열린 구간(무기한). */
    private static boolean outsideEffectiveRange(FixedWindow window, LocalDate occurrence) {
        return (window.effectiveFrom() != null && occurrence.isBefore(window.effectiveFrom()))
                || (window.effectiveTo() != null && occurrence.isAfter(window.effectiveTo()));
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * V3: 요일 배치 총량(분) &gt; 해당 요일 가용 총량 → WARNING. 초과 요일당 1건.
     * (계약 §3.3 V3: "요일 배치 총량(분) > 해당 요일 가용 총량")
     */
    private List<ValidationIssue> checkCapacityExceeded(PlanSnapshot s) {
        Map<DayOfWeek, Long> placedMinutes = placedMinutesByWeekday(s);
        Map<DayOfWeek, Long> capacityMinutes = capacityMinutesByWeekday(s);

        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<DayOfWeek, Long> e : placedMinutes.entrySet()) {
            long capacity = capacityMinutes.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > capacity) {
                issues.add(new ValidationIssue(
                        RuleId.V3_CAPACITY_EXCEEDED, Severity.WARNING,
                        null, null, null, e.getKey(),
                        RuleMessages.v3CapacityExceeded(e.getKey(), capacity, e.getValue())));
            }
        }
        return issues;
    }

    /**
     * V4 가용 시간 밖 배치 (경고) — 계약 §3.3: "블록 구간이 해당 요일 가용 창에 완전히 포함되지 않음".
     *
     * <p><b>완전 포함</b>이 기준이다 — 일부만 걸쳐도 위반이다. 부분 허용으로 두면 09:00~18:00 가용에
     * 17:00~22:00 배치가 통과해, 사용자가 실제로 못 쓰는 시간에 계획이 서 있게 된다.
     *
     * <p><b>자정을 넘는 블록은 항상 위반이다.</b> 가용 창은 {@code startTime < endTime} 이라 자정을
     * 넘지 못하므로(DB {@code ck_availability_range}) 그런 블록을 담을 창이 존재할 수 없다. 요일 귀속은
     * V3 경계 e·V6 승계 — {@code startAt} 의 zone 요일에 전량 귀속하고 요일별로 쪼개지 않는다.
     *
     * <p>창이 여럿이면 <b>합집합</b>에 포함되는지를 본다. 창 하나하나와 비교하면 09:00~12:00 과
     * 12:00~18:00 로 나뉜 요일에서 11:00~13:00 배치가 어느 창에도 완전히 안 들어가 위반이 되는데,
     * 실제로는 가용 시간이 연속이라 쓸 수 있는 시간이다.
     */
    private List<ValidationIssue> checkOutOfAvailability(PlanSnapshot s) {
        Map<DayOfWeek, List<LocalTime[]>> windows = activeWindowsByWeekday(s);
        List<ValidationIssue> issues = new ArrayList<>();

        for (BlockView b : s.blocks()) {
            ZonedDateTime start = b.startAt().atZone(s.zone());
            ZonedDateTime end = b.endAt().atZone(s.zone());
            DayOfWeek weekday = start.getDayOfWeek();
            List<LocalTime[]> dayWindows = windows.getOrDefault(weekday, List.of());

            boolean crossesMidnight = !end.toLocalDate().equals(start.toLocalDate());
            boolean covered = !crossesMidnight
                    && isCoveredByUnion(start.toLocalTime(), end.toLocalTime(), dayWindows);
            if (covered) {
                continue;
            }
            issues.add(new ValidationIssue(
                    RuleId.V4_OUT_OF_AVAILABILITY, Severity.WARNING,
                    b.blockId(), null, b.taskId(), weekday,
                    RuleMessages.v4OutOfAvailability(
                            weekday, start.toLocalTime(), end.toLocalTime(), dayWindows)));
        }
        return issues;
    }

    /**
     * V5 WBS 기간 밖 배치 (경고) — 계약 §3.3: "TASK 블록 배치일 ∉ [wbsStart, wbsEnd]".
     *
     * <p><b>WBS 미설정 태스크는 판정 제외</b>다(계약 명시). 미설정을 위반으로 읽으면 WBS를 아직 안 쓴
     * 사용자의 모든 배치가 경고가 된다 — 기능을 안 쓰는 것이 잘못이 아니다.
     * 시작·끝 중 하나만 있는 경우도 구간이 성립하지 않으므로 제외한다.
     */
    private List<ValidationIssue> checkOutOfWbs(PlanSnapshot s) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (BlockView b : s.blocks()) {
            TaskFacts facts = taskFactsOf(s, b);
            if (facts == null || facts.wbsStart() == null || facts.wbsEnd() == null) {
                continue;
            }
            ZonedDateTime start = b.startAt().atZone(s.zone());
            LocalDate placedOn = start.toLocalDate();
            if (!placedOn.isBefore(facts.wbsStart()) && !placedOn.isAfter(facts.wbsEnd())) {
                continue;
            }
            issues.add(new ValidationIssue(
                    RuleId.V5_OUT_OF_WBS, Severity.WARNING,
                    b.blockId(), null, b.taskId(), start.getDayOfWeek(),
                    RuleMessages.v5OutOfWbs(start.getDayOfWeek(), placedOn,
                            facts.wbsStart(), facts.wbsEnd())));
        }
        return issues;
    }

    /**
     * V6 마감일 이후 배치 (경고) — 계약 §3.3: "TASK 블록 배치일 > dueDate (null 제외)".
     *
     * <p>배치일 = zone 기준 {@code startAt} 의 날짜(계약 명시). 자정을 넘는 블록도 시작 날짜로 판단한다 —
     * 끝 날짜로 보면 23:00 시작 블록이 마감 당일에도 위반이 되어, 마감일에 일하는 정상 배치가 경고를 받는다.
     */
    private List<ValidationIssue> checkAfterDueDate(PlanSnapshot s) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (BlockView b : s.blocks()) {
            TaskFacts facts = taskFactsOf(s, b);
            if (facts == null || facts.dueDate() == null) {
                continue;
            }
            ZonedDateTime start = b.startAt().atZone(s.zone());
            LocalDate placedOn = start.toLocalDate();
            if (!placedOn.isAfter(facts.dueDate())) {
                continue;
            }
            issues.add(new ValidationIssue(
                    RuleId.V6_AFTER_DUE_DATE, Severity.WARNING,
                    b.blockId(), null, b.taskId(), start.getDayOfWeek(),
                    RuleMessages.v6AfterDueDate(start.getDayOfWeek(), placedOn, facts.dueDate())));
        }
        return issues;
    }

    /**
     * V7 버퍼 부족 (경고) — us-decisions-kr §2 Q2 확정 · 임계값 승인 D-19.
     *
     * <p>{@code buffer(d) = available(d) − planned(d)}, 발생 조건은
     * {@code 0 ≤ buffer(d)} <b>이고</b> {@code buffer(d) × 100 < available(d) × BUFFER_THRESHOLD_PCT}.
     * <b>정수 연산만</b> 쓴다(부동소수 금지, P1) — 비율을 나눗셈으로 구하면 반올림이 경계에서 갈린다.
     *
     * <p><b>V3와 상호 배타</b>인 것이 조건에서 저절로 따라온다: {@code planned > available} 이면
     * {@code buffer < 0} 이라 첫 조건이 깨진다. 같은 요일에 "초과"와 "여유 부족"이 함께 나오면
     * 사용자는 무엇을 해야 하는지 알 수 없다.
     *
     * <p>{@code available(d) = 0} 인 요일도 저절로 제외된다 — 배치가 있으면 {@code buffer < 0},
     * 배치가 없으면 {@code 0 < 0} 이 거짓이다. 계약이 "V7 미발생(배치가 있으면 V4 영역)"이라 한 것과 일치한다.
     */
    private List<ValidationIssue> checkBufferShortage(PlanSnapshot s) {
        Map<DayOfWeek, Long> placedMinutes = placedMinutesByWeekday(s);
        Map<DayOfWeek, Long> capacityMinutes = capacityMinutesByWeekday(s);

        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<DayOfWeek, Long> e : capacityMinutes.entrySet()) {
            long available = e.getValue();
            long planned = placedMinutes.getOrDefault(e.getKey(), 0L);
            long buffer = available - planned;
            if (buffer < 0 || buffer * 100 >= available * BUFFER_THRESHOLD_PCT) {
                continue;
            }
            issues.add(new ValidationIssue(
                    RuleId.V7_BUFFER_SHORTAGE, Severity.WARNING,
                    null, null, null, e.getKey(),
                    RuleMessages.v7BufferShortage(e.getKey(), available, planned)));
        }
        return issues;
    }

    /**
     * 요일별 배치 총 분 — V3·V7 공용.
     *
     * <p>요일 귀속은 zone 기준 {@code startAt} 이며 자정을 넘어도 쪼개지 않는다(계약 §3.3 경계 e).
     * V3와 V7이 각자 집계하면 <b>같은 요일에 서로 다른 총량을 보고</b> 상호 배타가 깨질 수 있다.
     */
    private static Map<DayOfWeek, Long> placedMinutesByWeekday(PlanSnapshot s) {
        Map<DayOfWeek, Long> placed = new EnumMap<>(DayOfWeek.class);
        for (BlockView b : s.blocks()) {
            DayOfWeek weekday = b.startAt().atZone(s.zone()).getDayOfWeek();
            placed.merge(weekday, Duration.between(b.startAt(), b.endAt()).toMinutes(), Long::sum);
        }
        return placed;
    }

    /** 요일별 활성 가용 총 분 — V3·V7 공용. 비활성 창은 세지 않는다. */
    private static Map<DayOfWeek, Long> capacityMinutesByWeekday(PlanSnapshot s) {
        Map<DayOfWeek, Long> capacity = new EnumMap<>(DayOfWeek.class);
        for (AvailabilityWindow a : s.availabilities()) {
            if (!a.active()) continue;
            capacity.merge(a.weekday(), Duration.between(a.startTime(), a.endTime()).toMinutes(), Long::sum);
        }
        return capacity;
    }

    /** 블록이 가리키는 태스크의 사실. TASK 블록이 아니거나 사실이 없으면 null. */
    private static TaskFacts taskFactsOf(PlanSnapshot s, BlockView b) {
        return b.taskId() == null ? null : s.taskFacts().get(b.taskId());
    }

    /** 요일별 활성 가용 창 — <b>시작 시각 오름차순</b>. 정렬은 문구·합집합 판정 양쪽의 전제다(P1). */
    private static Map<DayOfWeek, List<LocalTime[]>> activeWindowsByWeekday(PlanSnapshot s) {
        Map<DayOfWeek, List<LocalTime[]>> byWeekday = new EnumMap<>(DayOfWeek.class);
        for (AvailabilityWindow a : s.availabilities()) {
            if (!a.active()) continue;
            byWeekday.computeIfAbsent(a.weekday(), k -> new ArrayList<>())
                    .add(new LocalTime[]{a.startTime(), a.endTime()});
        }
        for (List<LocalTime[]> windows : byWeekday.values()) {
            windows.sort(Comparator.comparing(w -> w[0]));
        }
        return byWeekday;
    }

    /**
     * {@code [start, end)} 가 창들의 <b>합집합</b>에 완전히 포함되는가.
     *
     * <p>창을 시작 시각 순으로 훑으며 커버된 지점을 앞으로 민다. 인접·중첩 창은 이어진 것으로 본다 —
     * 09:00~12:00 과 12:00~18:00 사이에는 쓸 수 없는 시간이 없다.
     */
    private static boolean isCoveredByUnion(LocalTime start, LocalTime end, List<LocalTime[]> windows) {
        if (!start.isBefore(end)) {
            return false; // 길이 0 이하 — 정상 배치가 아니다.
        }
        LocalTime reached = start;
        for (LocalTime[] w : windows) {
            if (w[0].isAfter(reached)) {
                break; // 빈틈이 생겼다.
            }
            if (w[1].isAfter(reached)) {
                reached = w[1];
            }
            if (!reached.isBefore(end)) {
                return true;
            }
        }
        return false;
    }
}
