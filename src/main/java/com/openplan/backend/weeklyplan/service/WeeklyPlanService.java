package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.PlanBlockResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanCreateRequest;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 주간 계획 유스케이스 파사드 (ST-B2-07). 시각은 {@link UserClock}(P-2).
 *
 * <p>본 스토리는 생성(POST)·조회(GET)만. 블록 쓰기는 ST-B2-08, 검증·확정은 ST-B2-09.
 */
@Service
public class WeeklyPlanService {

    private static final int WEEK_SPAN_DAYS = 6; // 7일 주 → end = start + 6

    private final WeeklyPlanRepository repository;
    private final PlanBlockRepository planBlockRepository;
    private final UserClock clock;

    public WeeklyPlanService(WeeklyPlanRepository repository, PlanBlockRepository planBlockRepository,
                             UserClock clock) {
        this.repository = repository;
        this.planBlockRepository = planBlockRepository;
        this.clock = clock;
    }

    /**
     * 주간 계획 get-or-create (PLAN-02·20 / 정본 getOrCreateWeeklyPlan). 있으면 기존 반환, 없으면 생성 —
     * <b>주차 중복은 오류가 아니다</b>(재호출 멱등). {@link GetOrCreateResult#created()}로 컨트롤러가 201/200을 가른다.
     *
     * <p>응답은 계획 식별·상태만 담는다(blocks·요약은 GET에서) — get-or-create에 조인 비용을 얹지 않는다.
     * 동시 요청 경합은 DB UNIQUE(user_id,week_start_date)가 백스톱 — 충돌 시 기존을 재조회해 기존 반환으로 수렴한다.
     */
    @Transactional
    public GetOrCreateResult getOrCreate(UUID userId, WeeklyPlanCreateRequest req) {
        LocalDate start = req.weekStartDate();

        var existing = repository.findByUserIdAndWeekStartDate(userId, start);
        if (existing.isPresent()) {
            return new GetOrCreateResult(WeeklyPlanResponse.from(existing.get(), List.of()), false);
        }

        WeeklyPlan plan = new WeeklyPlan(userId, start, start.plusDays(WEEK_SPAN_DAYS), clock.now());
        try {
            repository.saveAndFlush(plan); // flush로 UNIQUE 위반을 이 지점에서 잡는다(경합 방어)
            return new GetOrCreateResult(WeeklyPlanResponse.from(plan, List.of()), true);
        } catch (DataIntegrityViolationException race) { // 동시 생성 경합 → 기존 반환으로 수렴
            WeeklyPlan winner = repository.findByUserIdAndWeekStartDate(userId, start).orElseThrow(() -> race);
            return new GetOrCreateResult(WeeklyPlanResponse.from(winner, List.of()), false);
        }
    }

    /** get-or-create 결과 — 컨트롤러가 {@code created}로 201(생성)/200(기존)을 가른다. */
    public record GetOrCreateResult(WeeklyPlanResponse plan, boolean created) {
    }

    /**
     * 주간 계획 조회 (PLAN-01·02). 주차별 단건 + 요약(사용시간·블록수) + 캘린더 렌더링용 blocks 목록(start_at 순).
     * 읽기 — 서비스 tx 없음.
     *
     * <p><b>없는 주차는 오류가 아니다</b> — 200 + 빈 응답(null 반환 → 봉투 {@code data} 없음). "이 주는 아직 계획 없음"을
     * 정상 상태로 표현한다(FE는 data 유무로 판단, 별도 404 처리 불요). 타인 주차도 소유 스코프에서 빠져 동일하게 빈 응답.
     */
    public WeeklyPlanResponse getByWeek(UUID userId, LocalDate weekStartDate) {
        return repository.findByUserIdAndWeekStartDate(userId, weekStartDate)
                .map(plan -> {
                    List<PlanBlockResponse> blocks = planBlockRepository
                            .findViewsByWeeklyPlanId(plan.getId())
                            .stream().map(PlanBlockResponse::fromView).toList();
                    return WeeklyPlanResponse.from(plan, blocks);
                })
                .orElse(null); // 없는 주차 → 200 + 빈 응답
    }
}
