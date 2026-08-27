package com.openplan.backend.stats.service;

import com.openplan.backend.stats.dto.CorrectionProposalResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보정 제안 산출식 단위 테스트 (SS-11 / RB-STAT-02) — 순수 함수라 DB·Spring 불요.
 *
 * <p>[G1] 식 골든 매트릭스: r 부호 × 반올림 경계 × 클램프 × 스코프 3종 문구.
 * <b>파라미터를 바꾸면 이 테스트가 깨지는 것이 정본 갱신 신호다</b>(의도된 마찰 — ASSUMPTION-CP1~CP5).
 */
class CorrectionProposalPolicyTest {

    private static final CorrectionProposalPolicy.Scope CATEGORY = CorrectionProposalPolicy.Scope.CATEGORY;

    private static CorrectionProposalResponse eval(int est, double rate, int sample) {
        return CorrectionProposalPolicy.evaluate(est, rate, sample, CATEGORY);
    }

    // ═══════════════ 식 본체 ═══════════════

    @Test
    @DisplayName("AC-2 식 골든 — est=60·r=+20 → 72 → round5 → 70 · basis·sampleSize 동봉")
    void baseFormula() {
        CorrectionProposalResponse p = eval(60, 20.0, 3);

        assertThat(p.proposedEstimatedMinutes()).isEqualTo(70); // 60×1.2 = 72 → 최근접 5 = 70
        assertThat(p.basis()).isEqualTo("해당 카테고리 편차율 +20% 반영");
        assertThat(p.sampleSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-4 음수 편차(대칭) — est=60·r=-20 → 48 → 50")
    void negativeRate() {
        CorrectionProposalResponse p = eval(60, -20.0, 5);

        assertThat(p.proposedEstimatedMinutes()).isEqualTo(50);
        assertThat(p.basis()).isEqualTo("해당 카테고리 편차율 -20% 반영");
    }

    @Test
    @DisplayName("AC-6 r=0 → 제안 반환(입력값 그대로) · null 아님 — '조정할 것 없음'도 사실이다")
    void zeroRateStillProposes() {
        CorrectionProposalResponse p = eval(60, 0.0, 3);

        assertThat(p).isNotNull();
        assertThat(p.proposedEstimatedMinutes()).isEqualTo(60);
        assertThat(p.basis()).isEqualTo("해당 카테고리 편차율 +0% 반영");
    }

    @Test
    @DisplayName("표시값과 계산값이 같은 정수 r을 쓴다 — basis가 '+20%'면 실제로 20을 곱해야 검산이 된다")
    void displayedRateMatchesComputation() {
        // rate 20.4 → r=20 → 60×1.20 = 72 → 70. 소수로 계산하면 60×1.204=72.24로 값이 갈릴 수 있다.
        CorrectionProposalResponse p = eval(60, 20.4, 3);

        assertThat(p.basis()).isEqualTo("해당 카테고리 편차율 +20% 반영");
        assertThat(p.proposedEstimatedMinutes()).isEqualTo(70);
    }

    // ═══════════════ 반올림 경계 (CP-4 half-up) ═══════════════

    @Nested
    @DisplayName("AC-3 반올림 경계 — 대칭 배치로 방향 실수를 잡는다")
    class RoundingBoundary {

        @Test
        @DisplayName("raw 62.5 → 65 (half-up)")
        void halfUp() {
            assertThat(eval(50, 25.0, 3).proposedEstimatedMinutes()).isEqualTo(65); // 50×1.25 = 62.5
        }

        @Test
        @DisplayName("raw 62 → 60 (내림 쪽)")
        void roundsDown() {
            assertThat(eval(50, 24.0, 3).proposedEstimatedMinutes()).isEqualTo(60); // 50×1.24 = 62
        }
    }

    // ═══════════════ 클램프 (CP-5) ═══════════════

    @Nested
    @DisplayName("AC-5 극단값 — 하한은 있고 상한은 없다")
    class Extremes {

        @Test
        @DisplayName("하한 — 반올림 결과가 5 미만이면 5로 올린다")
        void lowerClamp() {
            assertThat(eval(5, -90.0, 3).proposedEstimatedMinutes()).isEqualTo(5); // 5×0.1 = 0.5 → 0 → 5
        }

        @Test
        @DisplayName("하한 — r ≤ -100(실제시간 0에 수렴)이어도 5")
        void lowerClampAtMinusHundred() {
            assertThat(eval(60, -100.0, 3).proposedEstimatedMinutes()).isEqualTo(5); // 60×0 = 0 → 5
        }

        @Test
        @DisplayName("거대 입력에도 넘치지 않는다 — int 오버플로로 '5'가 나오면 안 된다")
        void hugeInputDoesNotOverflow() {
            // (int) 캐스트를 곱셈 전에 하면 440000000×5가 int를 넘어 음수가 되고,
            // 하한 클램프가 그 음수를 5로 끌어올려 "터무니없이 작은 값"이 조용히 나간다.
            CorrectionProposalResponse p = eval(1_000_000_000, 120.0, 3);

            assertThat(p.proposedEstimatedMinutes()).isNotEqualTo(5);      // 오버플로 증상
            assertThat(p.proposedEstimatedMinutes()).isPositive();
            assertThat(p.proposedEstimatedMinutes() % 5).isZero();          // 상한에서도 5배수 계약 유지
            assertThat(p.proposedEstimatedMinutes())
                    .isEqualTo(CorrectionProposalPolicy.MAX_PROPOSED_MINUTES);
        }

        @Test
        @DisplayName("🔴 상한 없음의 실물 — est=60·r=+300 → 240 그대로 (ASSUMPTION-CP5)")
        void noUpperClamp() {
            // 이 셀이 CP-5 확정의 판단 자료다. 완충·감쇠를 도입하면 여기가 깨지는 것이 정본 갱신 신호.
            CorrectionProposalResponse p = eval(60, 300.0, 3);

            assertThat(p.proposedEstimatedMinutes()).isEqualTo(240); // 60×4.0 = 240
            assertThat(p.basis()).isEqualTo("해당 카테고리 편차율 +300% 반영");
        }
    }

    // ═══════════════ 표본 하한 (CP-2) ═══════════════

    @Nested
    @DisplayName("AC-6 표본 경계 — 3건 미만이면 제안하지 않는다")
    class SampleBoundary {

        @Test
        @DisplayName("2건 → null")
        void belowThreshold() {
            assertThat(eval(60, 20.0, 2)).isNull();
        }

        @Test
        @DisplayName("3건 → 제안 (경계 반대편)")
        void atThreshold() {
            assertThat(eval(60, 20.0, 3)).isNotNull();
        }
    }

    // ═══════════════ 스코프 문구 (CP-3) ═══════════════

    @Test
    @DisplayName("스코프 3종 basis 문구 골든 — 정본 예문 형태를 승계하되 '최근'은 없다(CP-1 귀결)")
    void scopeLabels() {
        assertThat(CorrectionProposalPolicy.evaluate(60, 20.0, 3, CorrectionProposalPolicy.Scope.CATEGORY)
                .basis()).isEqualTo("해당 카테고리 편차율 +20% 반영");
        assertThat(CorrectionProposalPolicy.evaluate(60, 20.0, 3, CorrectionProposalPolicy.Scope.PROJECT)
                .basis()).isEqualTo("해당 프로젝트 편차율 +20% 반영");
        assertThat(CorrectionProposalPolicy.evaluate(60, 20.0, 3, CorrectionProposalPolicy.Scope.ALL)
                .basis()).isEqualTo("전체 수행 이력 편차율 +20% 반영");
    }

    @Test
    @DisplayName("AC-14 P4 — basis에 AI·자동 분석 표현이 없다")
    void basisHasNoAiWording() {
        String basis = eval(60, 20.0, 3).basis();

        assertThat(basis).doesNotContain("AI", "인공지능", "머신러닝", "자동 분석", "예측", "추천");
        assertThat(basis).doesNotContain("최근"); // CP-1 — 전체 이력이라 "최근"은 거짓 서술이 된다
    }

    // ═══════════════ 결정성 (P1) ═══════════════

    @Test
    @DisplayName("결정성 — 같은 입력 2회가 문자 단위로 동일")
    void deterministic() {
        assertThat(eval(60, 20.4, 7)).isEqualTo(eval(60, 20.4, 7));
    }

    @Test
    @DisplayName("모든 제안값은 5의 배수다 (정본 multipleOf: 5)")
    void alwaysMultipleOfFive() {
        for (int est = 5; est <= 300; est += 5) {
            for (double rate : new double[]{-99, -50, -13, 0, 7, 33, 150, 300}) {
                CorrectionProposalResponse p = eval(est, rate, 3);
                assertThat(p.proposedEstimatedMinutes() % 5)
                        .as("est=%d rate=%.0f → %d", est, rate, p.proposedEstimatedMinutes())
                        .isZero();
                assertThat(p.proposedEstimatedMinutes()).isGreaterThanOrEqualTo(5);
            }
        }
    }
}
