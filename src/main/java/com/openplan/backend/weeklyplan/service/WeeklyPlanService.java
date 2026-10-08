package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.FixedScheduleWeekView;
import com.openplan.backend.weeklyplan.dto.PlanBlockResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanCreateRequest;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanView;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
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
    private final FixedScheduleWeekAssembler fixedScheduleAssembler;
    private final UserClock clock;

    public WeeklyPlanService(WeeklyPlanRepository repository, PlanBlockRepository planBlockRepository,
                             FixedScheduleWeekAssembler fixedScheduleAssembler, UserClock clock) {
        this.repository = repository;
        this.planBlockRepository = planBlockRepository;
        this.fixedScheduleAssembler = fixedScheduleAssembler;
        this.clock = clock;
    }

    /**
     * 주간 계획 get-or-create (PLAN-02·20 / 정본 getOrCreateWeeklyPlan). 있으면 기존 반환, 없으면 생성 —
     * <b>주차 중복은 오류가 아니다</b>(재호출 멱등). {@link GetOrCreateResult#created()}로 컨트롤러가 201/200을 가른다.
     *
     * <p>응답은 계획 식별·상태만 담는다(blocks·요약은 GET에서) — get-or-create에 조인 비용을 얹지 않는다.
     *
     * <p><b>동시 요청 경합 방어</b>: {@code INSERT ... ON CONFLICT DO NOTHING}(예외 없음) 후 재조회해 승자 행으로
     * 수렴한다. {@code saveAndFlush} + {@code catch(DataIntegrityViolationException)}는 유니크 위반이 트랜잭션을
     * rollback-only로 마킹해, catch로 삼켜도 커밋 시점에 {@code UnexpectedRollbackException}(500)이 나므로 쓰지
     * 않는다(주차예외·주차이동 슬라이스에서 확인된 함정 — {@link
     * com.openplan.backend.weeklyplan.service.PlanBlockService#getOrCreateWeekPlan}과 동일 처방).
     */
    @Transactional
    public GetOrCreateResult getOrCreate(UUID userId, WeeklyPlanCreateRequest req) {
        LocalDate start = req.weekStartDate();

        var existing = repository.findByUserIdAndWeekStartDate(userId, start);
        if (existing.isPresent()) {
            return new GetOrCreateResult(WeeklyPlanResponse.from(existing.get(), 0), false);
        }

        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(), userId, start, start.plusDays(WEEK_SPAN_DAYS), clock.now());
        WeeklyPlan plan = repository.findByUserIdAndWeekStartDate(userId, start)
                .orElseThrow(() -> new IllegalStateException(
                        "insertIfAbsent 직후 주간계획 재조회 실패 — user=" + userId + " week=" + start));
        return new GetOrCreateResult(WeeklyPlanResponse.from(plan, 0), inserted > 0);
    }

    /** get-or-create 결과 — 컨트롤러가 {@code created}로 201(생성)/200(기존)을 가른다. */
    public record GetOrCreateResult(WeeklyPlanResponse plan, boolean created) {
    }

    /**
     * 주간 계획 조회 (PLAN-01·02). 주차별 단건 + 요약(사용시간·블록수) + 캘린더 렌더링용 blocks 목록(start_at 순)
     * + 그 주 고정 일정(이슈 #90). 읽기 — 서비스 tx 없음.
     *
     * <p><b>없는 주차는 오류가 아니다</b> — 200 + {@code data.plan = null}(정본 WeeklyPlanView). "이 주는 아직
     * 계획 없음"을 정상 상태로 표현한다(FE는 {@code data.plan} 유무로 판단, 별도 404 처리 불요). 타인 주차도 소유
     * 스코프에서 빠져 동일하게 {@code plan=null}. 봉투 {@code data}는 항상 존재(view 객체).
     *
     * <p><b>{@code fixedSchedules}는 plan 유무와 무관하게 채운다</b> — 고정 일정은 계획이 없는 주에도
     * 그 주 화면(요일 그리드)에 표시돼야 하므로, plan=null 분기에서도 조립해 둔다(이슈 #90 요구사항).
     */
    public WeeklyPlanView getByWeek(UUID userId, LocalDate weekStartDate) {
        List<FixedScheduleWeekView> fixedSchedules = fixedScheduleAssembler.assemble(userId, weekStartDate);
        return repository.findByUserIdAndWeekStartDate(userId, weekStartDate)
                .map(plan -> {
                    List<PlanBlockResponse> blocks = planBlockRepository
                            .findViewsByWeeklyPlanId(plan.getId())
                            .stream().map(PlanBlockResponse::fromView).toList();
                    return WeeklyPlanView.of(WeeklyPlanResponse.from(plan, blocks.size()), blocks, fixedSchedules);
                })
                .orElse(WeeklyPlanView.empty(fixedSchedules)); // 없는 주차 → 200 + data.plan=null, fixedSchedules는 유지
    }
}
