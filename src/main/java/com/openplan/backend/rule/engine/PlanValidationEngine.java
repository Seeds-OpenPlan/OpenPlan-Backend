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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 계획 적정성 검증 엔진 (rule-engine-contract §2·§3.3).
 *
 * C-1(결정성): 이 패키지에 Spring·JPA·java.time.Clock·Random·네트워크 import 금지.
 *              시각은 오직 {@code snapshot.referenceTime()}. (W6 ArchUnit로 강제 예정)
 * C-2: 저장소 핸들 없음 — 순수 함수, 자동 확정 불가.
 *
 * 구현 현황: V3(가용시간 초과)만. V1·V2·V4·V5·V6 = 김에스더 확장(TODO). V7 = Q2 확정 전 미생성.
 */
public final class PlanValidationEngine implements PlanValidationPort {

    /** 이슈 정렬(출력 결정성): severity(BLOCK 먼저) → ruleId → planBlockId(UUID ASC, null 최후) → weekday(null 최후). */
    private static final Comparator<ValidationIssue> ORDER =
            Comparator.comparingInt((ValidationIssue i) -> i.severity().ordinal())
                    .thenComparingInt(i -> i.ruleId().ordinal())
                    .thenComparing(ValidationIssue::planBlockId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ValidationIssue::weekday, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    public ValidationReport validate(PlanSnapshot snapshot) {
        List<ValidationIssue> issues = new ArrayList<>();

        issues.addAll(checkCapacityExceeded(snapshot)); // V3

        // TODO(김에스더 확장 — rule-engine-contract §3.3):
        //   V1_OVERLAP(차단) · V2_FIXED_CONFLICT(차단) · V4_OUT_OF_AVAILABILITY(경고)
        //   V5_OUT_OF_WBS(경고) · V6_AFTER_DUE_DATE(경고)
        //   V7_BUFFER_SHORTAGE: Q2 확정 전 생성 금지(값 창작 금지).

        issues.sort(ORDER);
        boolean savable = issues.stream().noneMatch(i -> i.severity() == Severity.BLOCK);
        return new ValidationReport(savable, List.copyOf(issues), snapshot.referenceTime());
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
                        null, null, e.getKey(),
                        RuleMessages.v3CapacityExceeded(e.getKey())));
            }
        }
        return issues;
    }
}
