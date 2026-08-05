package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.RuleId;
import com.openplan.backend.rule.model.Severity;
import com.openplan.backend.rule.model.ValidationIssue;
import com.openplan.backend.rule.model.ValidationReport;
import com.openplan.backend.rule.port.PlanValidationPort;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
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
 * 구현 현황: V1(겹침·차단) · V3(가용시간 초과·경고).
 *   V2·V4·V5·V6 = 미구현. V7 = 임계값 승인 완료(D-19, ASSUMPTION-Q2 버퍼 10%)이나 구현은 W6 예정.
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
        issues.addAll(checkCapacityExceeded(snapshot)); // V3 (WARNING)

        // 미구현 — rule-engine-contract §3.3:
        //   V2_FIXED_CONFLICT(차단) · V4_OUT_OF_AVAILABILITY(경고)
        //   V5_OUT_OF_WBS(경고) · V6_AFTER_DUE_DATE(경고)
        //   V7_BUFFER_SHORTAGE: 임계값 10% 승인 완료(D-19) — 구현은 W6.

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
        Map<DayOfWeek, Long> placedMinutes = new EnumMap<>(DayOfWeek.class);
        for (BlockView b : s.blocks()) {
            DayOfWeek weekday = b.startAt().atZone(s.zone()).getDayOfWeek(); // zone 기준 요일
            long minutes = Duration.between(b.startAt(), b.endAt()).toMinutes();
            placedMinutes.merge(weekday, minutes, Long::sum);
        }

        Map<DayOfWeek, Long> capacityMinutes = new EnumMap<>(DayOfWeek.class);
        for (AvailabilityWindow a : s.availabilities()) {
            if (!a.active()) continue;
            long minutes = Duration.between(a.startTime(), a.endTime()).toMinutes();
            capacityMinutes.merge(a.weekday(), minutes, Long::sum);
        }

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
}
