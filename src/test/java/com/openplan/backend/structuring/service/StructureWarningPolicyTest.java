package com.openplan.backend.structuring.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.structuring.domain.StructureWarningAction;
import com.openplan.backend.structuring.domain.StructureWarningType;
import com.openplan.backend.structuring.dto.StructureWarningResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구조 부족 경고 판정 단위 테스트 (SS-04 / RB-PROJ-02) — 순수 함수라 DB·Spring 불요.
 *
 * <p>[G1] 임계 경계 골든 · [G2] RB-PROJ-01 교차 불변식 · 상태별 판정표 · action 매핑 · P4를 고정한다.
 * <b>임계값을 바꾸면 이 테스트가 깨지는 것이 정본 갱신 신호다</b>(의도된 마찰 — ASSUMPTION-SW1~SW3).
 */
class StructureWarningPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static List<StructureWarningResponse> evaluate(ProjectStatus status, LocalDate dueDate,
                                                           long total, long remaining, long missing) {
        return StructureWarningPolicy.evaluate(status, dueDate, TODAY, total, remaining, missing);
    }

    private static List<StructureWarningType> typesOf(List<StructureWarningResponse> warnings) {
        return warnings.stream().map(StructureWarningResponse::warningType).toList();
    }

    // ═══════════════ [G1] 임계 경계 골든 ═══════════════

    @Nested
    @DisplayName("[G1] TOO_FEW_TASKS 경계 — 3건 미만")
    class TooFewBoundary {

        @Test
        @DisplayName("태스크 2건 → 발생 · 문구·action 골든")
        void twoTasksWarns() {
            List<StructureWarningResponse> w = evaluate(ProjectStatus.IN_PROGRESS, null, 2, 2, 0);

            assertThat(w).hasSize(1);
            assertThat(w.get(0).warningType()).isEqualTo(StructureWarningType.TOO_FEW_TASKS);
            assertThat(w.get(0).reason()).isEqualTo("태스크가 2건입니다. (기준 3건 미만)");
            assertThat(w.get(0).action()).isEqualTo(StructureWarningAction.ADD_TASK);
        }

        @Test
        @DisplayName("태스크 3건 → 미발생 (경계 반대편)")
        void threeTasksSilent() {
            assertThat(evaluate(ProjectStatus.IN_PROGRESS, null, 3, 3, 0)).isEmpty();
        }

        @Test
        @DisplayName("빈 프로젝트(0건) → TOO_FEW 1건만 (미완료 0이라 MISSING·DEADLINE 미성립)")
        void emptyProjectOnlyTooFew() {
            List<StructureWarningResponse> w =
                    evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(1), 0, 0, 0);

            assertThat(typesOf(w)).containsExactly(StructureWarningType.TOO_FEW_TASKS);
            assertThat(w.get(0).reason()).isEqualTo("태스크가 0건입니다. (기준 3건 미만)");
        }

        @Test
        @DisplayName("총량 2건·전량 COMPLETED → TOO_FEW만 (스코프 비대칭의 핵심 케이스)")
        void allCompletedStillTooFew() {
            // 구조는 완료 여부와 무관한 프로젝트 속성이라 total 기준. 미완료 0이라 나머지 둘은 미성립.
            List<StructureWarningResponse> w =
                    evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(1), 2, 0, 0);

            assertThat(typesOf(w)).containsExactly(StructureWarningType.TOO_FEW_TASKS);
        }
    }

    @Nested
    @DisplayName("[G1] MISSING_ESTIMATES — 1건 이상")
    class MissingEstimates {

        @Test
        @DisplayName("1건 → 발생 · 문구·action 골든")
        void oneMissingWarns() {
            List<StructureWarningResponse> w = evaluate(ProjectStatus.IN_PROGRESS, null, 5, 3, 1);

            assertThat(typesOf(w)).containsExactly(StructureWarningType.MISSING_ESTIMATES);
            assertThat(w.get(0).reason()).isEqualTo("예상시간이 비어 있는 미완료 태스크가 1건 있습니다.");
            assertThat(w.get(0).action()).isEqualTo(StructureWarningAction.EDIT_TASK);
        }

        @Test
        @DisplayName("2건 → 건수가 문구에 정확히 집계된다")
        void twoMissingCountsExactly() {
            List<StructureWarningResponse> w = evaluate(ProjectStatus.IN_PROGRESS, null, 5, 3, 2);

            assertThat(w.get(0).reason()).isEqualTo("예상시간이 비어 있는 미완료 태스크가 2건 있습니다.");
        }

        @Test
        @DisplayName("0건 → 미발생")
        void noMissingSilent() {
            assertThat(evaluate(ProjectStatus.IN_PROGRESS, null, 5, 3, 0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("[G1] DEADLINE_PRESSURE 경계 — 0~3일")
    class DeadlineBoundary {

        @Test
        @DisplayName("D+3 → 발생 / D+4 → 미발생 (경계 양쪽)")
        void threeDaysWarnsFourDoesNot() {
            assertThat(typesOf(evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(3), 5, 2, 0)))
                    .containsExactly(StructureWarningType.DEADLINE_PRESSURE);
            assertThat(evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(4), 5, 2, 0)).isEmpty();
        }

        @Test
        @DisplayName("마감 당일(D0) → 발생 · '마감까지 0일' (자동 종료 경계가 dueDate < today라 당일은 진행 중)")
        void dueTodayWarns() {
            List<StructureWarningResponse> w = evaluate(ProjectStatus.IN_PROGRESS, TODAY, 5, 2, 0);

            assertThat(w.get(0).reason()).isEqualTo("마감까지 0일, 미완료 태스크가 2건 남았습니다.");
            assertThat(w.get(0).action()).isEqualTo(StructureWarningAction.EDIT_TASK);
        }

        @Test
        @DisplayName("dueDate null(무기한) → 미발생")
        void noDueDateSilent() {
            assertThat(evaluate(ProjectStatus.IN_PROGRESS, null, 5, 2, 0)).isEmpty();
        }

        @Test
        @DisplayName("미완료 0건(전부 완료) → 마감이 임박해도 미발생")
        void nothingRemainingSilent() {
            assertThat(evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(1), 5, 0, 0)).isEmpty();
        }

        @Test
        @DisplayName("과거 마감(D-1) → 미발생 — 음수 일 문구가 새지 않는다(방어)")
        void pastDueDateSilent() {
            // B-5 평가 후엔 IN_PROGRESS에 과거 마감이 없지만, 평가를 건너뛴 호출자가 생겨도 안전해야 한다
            assertThat(evaluate(ProjectStatus.IN_PROGRESS, TODAY.minusDays(1), 5, 2, 0)).isEmpty();
        }
    }

    // ═══════════════ 상태별 판정표 ═══════════════

    @Test
    @DisplayName("CLOSED → 전체 억제(빈 배열) — 실행 불가능한 action을 제안하지 않는다")
    void closedSuppressesAll() {
        // 태스크 0건 + 마감 임박 + 예상시간 결손까지 갖춘 '3종 모두 성립' 상태여도 빈 배열
        assertThat(evaluate(ProjectStatus.CLOSED, TODAY, 0, 2, 2)).isEmpty();
    }

    @Test
    @DisplayName("PAUSED → TOO_FEW·MISSING 판정 / DEADLINE 미판정")
    void pausedJudgesTwoOfThree() {
        List<StructureWarningResponse> w = evaluate(ProjectStatus.PAUSED, TODAY, 2, 2, 1);

        assertThat(typesOf(w)).containsExactly(
                StructureWarningType.TOO_FEW_TASKS, StructureWarningType.MISSING_ESTIMATES);
    }

    @Test
    @DisplayName("PAUSED + 과거 마감 → 음수 일 경고 없음")
    void pausedWithPastDueDateNoNegativeDays() {
        List<StructureWarningResponse> w = evaluate(ProjectStatus.PAUSED, TODAY.minusDays(30), 5, 2, 0);

        assertThat(w).isEmpty();
    }

    @Test
    @DisplayName("PAUSED + 마감 임박(D+2) → DEADLINE 미발생 — 상태 필터가 실제로 가르는 지점")
    void pausedWithImminentDueDateStillSuppressed() {
        // 이 케이스가 상태 필터의 존재 이유다. 과거 마감(위 테스트)은 daysUntil >= 0 가드만으로도
        // 억제되므로, 그것만 있으면 status != IN_PROGRESS 조건을 지워도 테스트가 안 깨진다.
        // 여기서 억제를 잠가야 "대시보드와의 대칭"(DASH-06이 IN_PROGRESS만 대상) 결정이 지켜진다.
        List<StructureWarningResponse> w = evaluate(ProjectStatus.PAUSED, TODAY.plusDays(2), 5, 5, 0);

        assertThat(w).isEmpty();
    }

    // ═══════════════ 다중 발생 순서 ═══════════════

    @Test
    @DisplayName("3종 동시 → 정확히 3건 · 순서 TOO_FEW → MISSING → DEADLINE 고정")
    void multipleWarningsInDeclaredOrder() {
        List<StructureWarningResponse> w =
                evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(2), 2, 2, 2);

        assertThat(typesOf(w)).containsExactly(
                StructureWarningType.TOO_FEW_TASKS,
                StructureWarningType.MISSING_ESTIMATES,
                StructureWarningType.DEADLINE_PRESSURE);
        assertThat(w).extracting(StructureWarningResponse::reason).containsExactly(
                "태스크가 2건입니다. (기준 3건 미만)",
                "예상시간이 비어 있는 미완료 태스크가 2건 있습니다.",
                "마감까지 2일, 미완료 태스크가 2건 남았습니다.");
    }

    // ═══════════════ [G2] RB-PROJ-01 교차 불변식 ═══════════════

    @Test
    @DisplayName("[G2] 구조화 사전의 어떤 항목을 그대로 채택해도 경고 0건 — 두 규칙이 모순되면 안 된다")
    void adoptingAnyDictionaryEntryProducesNoWarning() throws Exception {
        JsonNode entries = readDictionary().get("entries");
        assertThat(entries).isNotEmpty();

        for (JsonNode entry : entries) {
            JsonNode drafts = entry.get("drafts");
            long total = drafts.size();
            long missing = 0;
            for (JsonNode d : drafts) {
                JsonNode est = d.get("estimatedMinutes");
                if (est == null || est.isNull()) {
                    missing++;
                }
            }

            // 초안을 막 채택한 직후 = 전량 미완료, 마감은 여유(D+7)
            List<StructureWarningResponse> w =
                    evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(7), total, total, missing);

            assertThat(w)
                    .as("사전 항목 %s(초안 %d건)를 채택했는데 경고가 났다 — RB-PROJ-01↔02 자기모순",
                            entry.get("key").asText(), total)
                    .isEmpty();
        }
    }

    private static JsonNode readDictionary() throws Exception {
        try (InputStream in = new ClassPathResource("structuring-dictionary-v1.json").getInputStream()) {
            return new ObjectMapper().readTree(in);
        }
    }

    // ═══════════════ P4 ═══════════════

    @Test
    @DisplayName("P4 — reason에 AI·자동 분석 표현이 없다(사실 서술만)")
    void reasonsHaveNoAiWording() {
        List<StructureWarningResponse> all =
                evaluate(ProjectStatus.IN_PROGRESS, TODAY.plusDays(1), 1, 1, 1);
        assertThat(all).hasSize(3); // 3종 전부 확보한 상태에서 문구를 훑는다

        for (StructureWarningResponse w : all) {
            assertThat(w.reason()).doesNotContain("AI", "인공지능", "머신러닝", "자동 분석", "분석했");
        }
    }
}
