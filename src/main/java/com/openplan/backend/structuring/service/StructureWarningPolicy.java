package com.openplan.backend.structuring.service;

import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.structuring.domain.StructureWarningAction;
import com.openplan.backend.structuring.domain.StructureWarningType;
import com.openplan.backend.structuring.dto.StructureWarningResponse;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 구조 부족 경고 판정 (SS-04 / RB-PROJ-02) — <b>순수 함수</b>.
 *
 * <p>저장소·시계·Spring을 주입받지 않는다(검증 엔진 C-1 순수성 관례 승계). {@code today}는 호출자가
 * {@code UserClock.todayOf(userId)}로 구해 넘긴다 — {@code LocalDate.now()} 직접 호출은 P-2 위반이다.
 * 사실 수집은 서비스가, 판정은 여기서만 한다 — 분기가 두 곳으로 갈라지면 골든과 드리프트가 생긴다.
 *
 * <p><b>임계값 3종은 W3 게이트에서 확정됐다</b>(ASSUMPTION-SW1~SW3, User 승인 2026-08-27).
 * 값을 바꾸려면 상수·문구·골든 테스트·openapi description을 함께 갱신해야 한다 — 의도된 마찰이며,
 * {@code PlanValidationEngine}의 {@code BUFFER_THRESHOLD_PCT}(ASSUMPTION-Q2)가 세운 선례를 따른다.
 */
public final class StructureWarningPolicy {

    /**
     * 〔ASSUMPTION-SW1〕 태스크 총수가 이 값 미만이면 "구조 부족".
     *
     * <p>유도: 형제 규칙 RB-PROJ-01의 정본 사전({@code structuring-dictionary-v1.json})이 제안하는
     * 최소 초안 구성이 3건(STUDY·GENERIC)이다. 시스템 스스로 "주간 계획 가능한 최소 형태"로 내놓는
     * 하한이 3건이므로 그 미만을 부족으로 본다. 셋 중 유일한 간접 유도라 게이트에서 명시 승인을 받았다.
     */
    static final int MIN_TASKS = 3;

    /**
     * 〔ASSUMPTION-SW3〕 마감까지 남은 일수가 이 값 이하면 "마감 압박".
     *
     * <p>유도: "마감 임박"의 공용 정의 {@code DEADLINE_SOON_DAYS = 3}(ASSUMPTION-D3)을 그대로 쓴다 —
     * 대시보드의 마감 임박 카운트·프로젝트 배지와 <b>같은 창</b>이어야 사용자가 두 화면에서 다른 말을
     * 듣지 않는다. 새 산식(일당 태스크 비율 등)은 정본 앵커가 없어 발명이 되므로 채택하지 않았다.
     */
    static final int DEADLINE_SOON_DAYS = 3;

    /** 〔ASSUMPTION-SW2〕 미완료 태스크 중 예상시간이 빈 것이 이 값 이상이면 경고. */
    static final int MIN_MISSING_ESTIMATES = 1;

    private StructureWarningPolicy() {
    }

    /**
     * 판정. 결과는 {@link StructureWarningType} <b>선언 순</b>으로, 유형당 최대 1건(최대 3건)이다.
     *
     * <p><b>CLOSED는 판정 자체를 억제</b>한다(빈 배열). 종료된 프로젝트는 태스크 쓰기가 전부
     * 422 E-PROJ-005(D-10)라, 경고를 내면 <b>수행할 수 없는 action</b>을 제안하는 셈이 된다.
     * 오류가 아니라 빈 배열인 이유는 조회 자체는 상태 무관 허용이기 때문이다(복제 프리뷰 선례).
     *
     * @param status         프로젝트 상태 — 자동 종료 평가(B-5)가 끝난 뒤의 값이어야 한다
     * @param dueDate        프로젝트 마감일(null = 무기한)
     * @param today          사용자 타임존 기준 오늘 (P-2)
     * @param totalTasks     태스크 총수 — <b>상태 무관</b>(구조는 완료 여부와 독립된 프로젝트 속성)
     * @param remainingTasks 미완료(status ≠ COMPLETED) 태스크 수
     * @param missingEstimates 미완료 중 예상시간이 null인 수
     */
    public static List<StructureWarningResponse> evaluate(ProjectStatus status, LocalDate dueDate, LocalDate today,
                                                          long totalTasks, long remainingTasks,
                                                          long missingEstimates) {
        if (status == ProjectStatus.CLOSED) {
            return List.of(); // 실행 불가능한 action을 제안하지 않는다
        }

        List<StructureWarningResponse> warnings = new ArrayList<>();

        if (totalTasks < MIN_TASKS) {
            warnings.add(new StructureWarningResponse(StructureWarningType.TOO_FEW_TASKS,
                    "태스크가 " + totalTasks + "건입니다. (기준 " + MIN_TASKS + "건 미만)",
                    StructureWarningAction.ADD_TASK));
        }

        if (missingEstimates >= MIN_MISSING_ESTIMATES) {
            warnings.add(new StructureWarningResponse(StructureWarningType.MISSING_ESTIMATES,
                    "예상시간이 비어 있는 미완료 태스크가 " + missingEstimates + "건 있습니다.",
                    StructureWarningAction.EDIT_TASK));
        }

        if (isDeadlinePressure(status, dueDate, today, remainingTasks)) {
            long daysUntil = ChronoUnit.DAYS.between(today, dueDate);
            warnings.add(new StructureWarningResponse(StructureWarningType.DEADLINE_PRESSURE,
                    "마감까지 " + daysUntil + "일, 미완료 태스크가 " + remainingTasks + "건 남았습니다.",
                    StructureWarningAction.EDIT_TASK));
        }

        return List.copyOf(warnings);
    }

    /**
     * 마감 압박 성립 여부. <b>IN_PROGRESS 한정</b>이다 — 근거는 아래 두 가지이며, 뒤의 날짜 가드와는
     * <b>겹치지 않는 별개 층</b>이다(그 구분이 중요해서 나눠 적는다).
     *
     * <ol>
     *   <li><b>대시보드와의 대칭</b>(주 근거). {@code DashboardService}가 "이번 주 영향 프로젝트"를
     *       IN_PROGRESS만 대상으로 뽑는다. 여기서 PAUSED에 마감 압박을 띄우면 <b>대시보드는 조용한데
     *       작업공간만 재촉하는</b> 상태가 되어, 같은 사용자에게 두 화면이 다른 말을 한다.</li>
     *   <li><b>일시중지의 의미</b>. PAUSED는 사용자가 의도적으로 손을 뗀 상태라, 멈춰둔 일에 대한
     *       마감 재촉은 도움보다 소음에 가깝다고 판단했다(W3 게이트 판단 — 뒤집으려면 재게이트가 절차다.
     *       AC-10과 지명 테스트 [G6]이 이 동작을 잠그고 있어 조용히 바뀌지 않는다).</li>
     * </ol>
     *
     * <p><b>"과거 마감일이라 음수 일이 무의미하다"는 이 상태 필터의 근거가 아니다</b> — 그 걱정은 아래
     * {@code daysUntil >= 0}이 이미 처리한다. 상태 필터가 없어도 과거 마감 PAUSED는 어차피 경고가 안 난다.
     * 상태 필터가 실제로 가르는 것은 <b>마감이 코앞인 PAUSED</b>(예: D+2, 미완료 5건)이고, 그것을 억제하는
     * 근거가 위 ①②다. (리뷰 지적 반영 — 이전 주석은 날짜 가드로 이미 해결되는 이유만 적어 두어 상태
     * 필터의 존재를 설명하지 못했다.)
     *
     * <p>{@code 0 <= daysUntil}은 방어적 명문이다 — B-5 평가 선행 후 IN_PROGRESS의 dueDate는 today 이상이
     * 보장되지만(자동 종료가 {@code dueDate < today}를 걷어낸다), 평가를 건너뛴 호출자가 생겨도 음수 일
     * 문구가 새어나가지 않게 한다. 마감 당일(0일)은 발생 — 아직 진행 중이고 압박이 가장 크다.
     */
    private static boolean isDeadlinePressure(ProjectStatus status, LocalDate dueDate, LocalDate today,
                                              long remainingTasks) {
        if (status != ProjectStatus.IN_PROGRESS || dueDate == null || remainingTasks < 1) {
            return false;
        }
        long daysUntil = ChronoUnit.DAYS.between(today, dueDate);
        return daysUntil >= 0 && daysUntil <= DEADLINE_SOON_DAYS;
    }
}
