package com.openplan.backend.stats.service;

import com.openplan.backend.stats.dto.CorrectionProposalResponse;

import java.math.BigInteger;

/**
 * 예상 시간 보정 제안 판정 (SS-11 / RB-STAT-02) — <b>순수 함수</b>.
 *
 * <p>저장소·시계·Spring을 주입받지 않는다(검증 엔진 C-1 순수성 승계, {@code StructureWarningPolicy} 선례).
 * 사실 수집은 {@link StatsService}가, 판정은 여기서만 한다 — 분기가 두 곳으로 갈라지면 골든과 드리프트가 생긴다.
 *
 * <p><b>파라미터는 W3 게이트에서 확정됐다</b>: ASSUMPTION-CP1·CP2·CP5 = User 승인(2026-08-27),
 * CP-3·CP-4 = 리드 승인. <b>재결정 절차(의도된 마찰)</b>: 어느 값이든 바꾸려면 이 클래스의 상수 +
 * basis 문구 + 골든 테스트(극단 셀 포함) + openapi description을 <b>한 커밋에서 동시 갱신</b>해야 한다 —
 * 하나만 갱신된 diff는 리뷰 반려 사유다(ASSUMPTION-SW1 선례).
 */
public final class CorrectionProposalPolicy {

    /**
     * 〔ASSUMPTION-CP2 · User 승인 2026-08-27〕 표본이 이 값 미만이면 제안하지 않는다(data 생략).
     *
     * <p>정본 "편차 이력 <b>부족</b> 시"는 0건("없음")과 구분되는 하한의 존재를 시사하고, 값 3은 이
     * 코드베이스가 반복 채택한 "시스템이 정의한 최소 충분"(구조화 사전 최소 초안 3건 · {@code MIN_TASKS=3}
     * · {@code DEADLINE_SOON_DAYS=3})의 재사용이다.
     *
     * <p>🔴 <b>약점을 알고 감수한 값이다(서술 삭제 금지)</b>: 직접 앵커가 없는 유비이고, 게이트 리뷰에서
     * "통계 표본 선례는 코드베이스에 0건"으로 실측됐다. 또한 표본 단위가 <b>로그 건수</b>라
     * <b>단일 태스크를 3번 기록한 것만으로도 하한이 충족된다</b> — "서로 다른 태스크 3건"이 아니다.
     * 실사용 데이터가 쌓이면 {@code execution_logs}에서 로그 건수 대비
     * {@code COUNT(DISTINCT task_id)} 비율로 이 하한의 실효를 재검토할 수 있다.
     */
    static final int MIN_SAMPLE_SIZE = 3;

    /**
     * 〔ASSUMPTION-CP5 · User 승인 2026-08-27〕 제안값 하한(분). 상한·감쇠는 <b>도입하지 않는다</b>.
     *
     * <p>하한 5는 대상 필드의 유효 도메인에서 구조 유도된다({@code ck_tasks_estimated} — 5분 배수·양수).
     *
     * <p>🔴 <b>상한이 없다는 것의 실제 의미</b>: 편차율 +300%면 60분 입력에 <b>240분</b>이 그대로 제안된다.
     * 상한(×2 등)·감쇠(반영률 50% 등) 어떤 수치도 전 정본·전 코드베이스에 앵커가 없어 <b>발명</b>이 되므로
     * 미도입을 채택했다 — 이는 "유도"가 아니라 "발명 회피"다. 방어선은 코드 밖에 있다: 자동 적용이 없고
     * (C-2/P2) 사용자가 basis·sampleSize를 보고 채택 여부를 결정하며, {@link #MIN_SAMPLE_SIZE}가
     * 소표본 이상치를 1차 여과한다. 완충 도입은 제품 재결정 사항이며, 그때 아래 극단 골든이 깨지는 것이
     * 정본 갱신 신호다.
     */
    static final int MIN_PROPOSED_MINUTES = 5;

    /** 제안값 정렬 단위(분) — 태스크 예상시간이 5분 배수라는 도메인 규약({@code ck_tasks_estimated}). */
    static final int STEP_MINUTES = 5;

    /**
     * 제안값 상한 — <b>표현 한계이지 제품 상한이 아니다</b>. ASSUMPTION-CP5가 기각한 "완충·감쇠"와는
     * 성격이 다르다: 그쪽은 "편차율을 얼마나 반영할 것인가"라는 제품 판단이고, 이것은 응답 필드가
     * {@code int}라서 넘을 수 없는 물리적 경계다.
     *
     * <p>값은 int 범위 안의 최대 5의 배수다 — {@code Integer.MAX_VALUE}(…647)로 자르면 "제안값은 5의
     * 배수"라는 계약이 깨진다. 여기에 닿으려면 입력이 수억 분이어야 해서 정상 사용에서는 도달하지 않고,
     * 도달하더라도 <b>넘쳐서 5가 되는 것</b>보다는 큰 값이 그대로 보이는 편이 정직하다(입력이 잘못됐다는
     * 신호가 사용자에게 남는다).
     */
    static final int MAX_PROPOSED_MINUTES = 2_147_483_645;

    /**
     * 산출을 정확한 정수 유리수로 하기 위한 상수들 — 분모 100(백분율)을 곱해 둔 "scaled" 좌표계다.
     * {@code scaled = estimatedMinutes × (100 + r)} 이고, 이는 {@code raw × 100} 과 같다.
     * 부동소수점을 거치지 않으므로 .5 경계가 문서화된 half-up 대로 정확히 위로 간다.
     */
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    private static final BigInteger MIN_SCALED = BigInteger.valueOf(MIN_PROPOSED_MINUTES * 100L);

    private static final BigInteger MAX_SCALED = BigInteger.valueOf(MAX_PROPOSED_MINUTES * 100L);

    private static final BigInteger STEP_SCALED = BigInteger.valueOf(STEP_MINUTES * 100L);

    /** half-up 을 정수 나눗셈으로 하기 위한 반 칸 = (5분 × 100) / 2. */
    private static final BigInteger HALF_STEP_SCALED = BigInteger.valueOf(STEP_MINUTES * 100L / 2);

    private CorrectionProposalPolicy() {
    }

    /** 편차율을 집계한 스코프 — basis 문구를 고른다. API에 노출되지 않는 내부 값이다. */
    public enum Scope {
        CATEGORY("해당 카테고리"),
        PROJECT("해당 프로젝트"),
        ALL("전체 수행 이력");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * 보정 제안 산출. 표본이 하한 미만이면 {@code null}(호출자가 data 생략으로 응답).
     *
     * <p>식:
     * <pre>
     *   r        = round(deviationRate)                 // 정수 — basis 표시값과 계산값을 일치시킨다
     *   raw      = estimatedMinutes × (100 + r) / 100
     *   proposed = max(5, round(raw / 5) × 5)
     * </pre>
     *
     * <p><b>계산과 표시에 같은 정수 r을 쓰는 것이 중요하다</b>: basis가 "+20% 반영"이라고 말하면 실제로 20을
     * 곱해야 사용자가 검산할 수 있다(P2 투명성). 소수 rate로 계산하고 정수로 표시하면 basis가 미세하게
     * 거짓이 된다. {@code Math.round}의 자바 시맨틱(half-up, 음수 포함)을 그대로 쓴다 — 결정적(P1).
     *
     * <p>편차율이 0이어도 제안을 반환한다(제안값 = 입력값, basis "+0% 반영") — "조정할 것이 없음"도
     * 사실이고, null은 "판단할 수 없음"이라 의미가 다르다.
     *
     * @param estimatedMinutes 사용자가 입력 중인 예상값 (호출 전 5분 단위 검증 완료)
     * @param deviationRate    스코프 편차율(%) — 호출자가 as-built {@code deviations()}와 동일 산법으로 집계
     * @param sampleSize       집계에 쓰인 <b>로그 건수</b>(태스크 건수가 아님)
     * @return 제안 · 표본 부족이면 null
     */
    public static CorrectionProposalResponse evaluate(int estimatedMinutes, double deviationRate,
                                                      int sampleSize, Scope scope) {
        if (sampleSize < MIN_SAMPLE_SIZE) {
            return null;
        }

        long r = Math.round(deviationRate);

        // 🔴 여기서 double 곱셈을 쓰면 안 된다 (2026-08-27 리뷰 지적 — 재현 확인).
        //    옛 식 `estimatedMinutes × ((100.0 + r) / 100.0)` 은 정확히 .5 경계에 오는 값을
        //    한 칸 내려보냈다: est=50 · r=+15 는 수학적으로 57.5 라 문서화된 half-up 대로면 60 인데,
        //    (100.0+15)/100.0 이 이진 부동소수점으로 1.1499999999999999 여서 raw 가 57.49999… 가 되고
        //    round(57.49999…/5)=11 → **55** 가 나갔다. est ≤ 1000 · r ∈ [-99,500] 만 훑어도 117 건이
        //    같은 방향(문서화된 값보다 항상 5분 낮음)으로 어긋난다. 기존 경계 골든 두 개(62.5→65,
        //    62→60)는 우연히 오차가 상쇄되는 조합이라 이 결함을 못 잡았다.
        //
        //    정수 유리수로 정확히 센다. long 곱셈으로 돌아가지 않는 이유는 옛 주석이 적어 둔 그대로다 —
        //    r 은 편차율에서 온 값이라 상한이 없어(ASSUMPTION-CP5 — 감쇠 미도입) long 이 넘치고,
        //    넘치면 부호가 뒤집혀 하한 5분이 조용히 나간다. BigInteger 는 넘치지 않으므로 그 위험이
        //    없으면서 반올림도 정확하다. 순수 함수라 비용은 문제가 되지 않는다(P1 결정성이 상위 규범).
        //
        //    scaled = raw × 100 (분모 100 을 곱해 둔 정수). 100 + r 도 BigInteger 로 더한다 —
        //    r = Long.MAX_VALUE 같은 값에서 `100L + r` 자체가 넘치기 때문이다.
        BigInteger scaled = BigInteger.valueOf(estimatedMinutes)
                .multiply(HUNDRED.add(BigInteger.valueOf(r)));

        // 정렬(5분 반올림) <b>전에</b> 클램프한다 — 순서가 중요하다. 하한 5도 상한도 5의 배수라
        // 클램프 결과는 그 자체로 정렬돼 있고, 정렬이 경계를 다시 넘지 못한다.
        if (scaled.compareTo(MIN_SCALED) <= 0) {
            return new CorrectionProposalResponse(MIN_PROPOSED_MINUTES, basis(scope, r), sampleSize);
        }
        if (scaled.compareTo(MAX_SCALED) >= 0) {
            return new CorrectionProposalResponse(MAX_PROPOSED_MINUTES, basis(scope, r), sampleSize);
        }

        // half-up 정렬: floor((raw×100 + 250) / 500) × 5. `Math.round` 의 자바 시맨틱(.5 는 위로,
        // 음수 포함)과 같은 규칙이며, 이 분기의 scaled 는 항상 양수라 BigInteger 의 0 방향 절삭이
        // floor 와 일치한다. 상·하한을 이미 잘라 둔 뒤라 결과는 int 안에 들어온다.
        long proposed = scaled.add(HALF_STEP_SCALED).divide(STEP_SCALED).longValueExact() * STEP_MINUTES;

        return new CorrectionProposalResponse((int) proposed, basis(scope, r), sampleSize);
    }

    /**
     * 근거 문구 (P4 — 사실 서술). 문자열 결합만 쓴다: 포매터·Locale에 기대면 환경에 따라 결과가 달라져
     * 골든이 깨진다(P1). 부호를 명시해 "+20%"·"-20%"로 읽히게 한다.
     *
     * <p>정본 예문(`"해당 카테고리 최근 편차율 +20% 반영"`)의 형태·어조를 승계하되 <b>"최근"만 뺀다</b> —
     * ASSUMPTION-CP1이 집계 창을 전체 이력으로 확정했으므로, "최근"은 지시 대상이 없는 거짓 서술이 된다.
     * P4(사실 서술)가 예문 문구보다 상위 규범이다.
     */
    private static String basis(Scope scope, long r) {
        String sign = r >= 0 ? "+" : "-";
        return scope.label() + " 편차율 " + sign + Math.abs(r) + "% 반영";
    }
}
