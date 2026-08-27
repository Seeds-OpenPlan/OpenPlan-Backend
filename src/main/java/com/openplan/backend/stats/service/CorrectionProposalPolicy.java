package com.openplan.backend.stats.service;

import com.openplan.backend.stats.dto.CorrectionProposalResponse;

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

        // `100.0 + r`의 소수점이 핵심이다 — 정수로 두면 `100 + r`과 `estimatedMinutes × (…)`이 둘 다
        // long 곱셈이 되고, r은 편차율에서 온 값이라 상한이 없어서(ASSUMPTION-CP5 — 감쇠 미도입) 넘친다.
        // 넘치면 부호가 뒤집혀 "터무니없이 큰 제안"이 아니라 하한 5분이 조용히 나간다 — 사용자에게 경고가 없다.
        // double은 넘치는 대신 큰 값을 그대로 들고 가 아래 상한 클램프가 받는다. |100+r| < 2^53 구간에서는
        // long 산술과 결과가 비트 단위로 같으므로 정상 범위의 골든은 움직이지 않는다.
        double raw = estimatedMinutes * ((100.0 + r) / 100.0);

        // 정렬(5분 반올림) <b>전에</b> 클램프한다 — 순서가 중요하다. 먼저 정렬하면 Infinity/5 가
        // Long.MAX_VALUE 로 반올림되고 거기에 5를 곱하는 순간 long이 넘쳐 음수가 된다.
        // 먼저 잘라두면 MAX_PROPOSED_MINUTES 가 5의 배수라서 정렬이 그 경계를 다시 넘지 못한다.
        double bounded = Math.min(MAX_PROPOSED_MINUTES, Math.max(MIN_PROPOSED_MINUTES, raw));
        long proposed = Math.round(bounded / STEP_MINUTES) * (long) STEP_MINUTES;

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
