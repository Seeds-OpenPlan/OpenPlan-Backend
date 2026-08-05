package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanCreateRequest;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final UserClock clock;

    public WeeklyPlanService(WeeklyPlanRepository repository, UserClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 주간 계획 생성 (ST-B2-07). status=DRAFT, weekEndDate=start+6. 같은 주차가 이미 있으면 409 E-PLAN-001.
     * "주차 진입 시 없으면 생성" 흐름의 백엔드 — FE는 GET(404) 후 POST한다.
     */
    @Transactional
    public WeeklyPlanResponse create(UUID userId, WeeklyPlanCreateRequest req) {
        LocalDate start = req.weekStartDate();

        if (repository.existsByUserIdAndWeekStartDate(userId, start)) { // 409 (UNIQUE(user_id,week_start_date))
            throw new OpenPlanException(ErrorCode.E_PLAN_001);
        }

        WeeklyPlan plan = new WeeklyPlan(userId, start, start.plusDays(WEEK_SPAN_DAYS), clock.now());
        repository.save(plan);
        return WeeklyPlanResponse.from(plan, 0); // 신규 계획 → 배치 블록 0
    }

    /**
     * 주간 계획 조회 (PLAN-01·02). 주차별 단건 + 요약(사용시간=total 캐시, 배치 블록 수). 읽기 — 서비스 tx 없음.
     *
     * <p><b>없는 주차는 오류가 아니다</b> — 200 + 빈 응답(null 반환 → 봉투 {@code data} 없음). "이 주는 아직 계획 없음"을
     * 정상 상태로 표현한다(FE는 data 유무로 판단, 별도 404 처리 불요). 타인 주차도 소유 스코프에서 빠져 동일하게 빈 응답.
     */
    public WeeklyPlanResponse getByWeek(UUID userId, LocalDate weekStartDate) {
        return repository.findByUserIdAndWeekStartDate(userId, weekStartDate)
                .map(plan -> WeeklyPlanResponse.from(plan, repository.countBlocks(plan.getId())))
                .orElse(null); // 없는 주차 → 200 + 빈 응답
    }
}
