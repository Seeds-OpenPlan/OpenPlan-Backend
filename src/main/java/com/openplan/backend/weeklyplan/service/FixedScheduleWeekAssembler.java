package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import com.openplan.backend.fixedschedule.repository.FixedScheduleRepository;
import com.openplan.backend.fixedschedule.repository.FixedScheduleWeekExceptionRepository;
import com.openplan.backend.weeklyplan.dto.FixedScheduleWeekView;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 주간 화면용 고정 일정 조립 (이슈 #90 — {@code WeeklyPlanView.fixedSchedules}). 세 지점
 * ({@link WeeklyPlanService#getByWeek}·{@link ReplanService#apply}·{@link PlanBlockService#applyBatch})이
 * 모두 "그 주 + 고정 일정" 뷰가 필요해 여기 한 곳에 모은다 — 겹침·활성 판정 지식이 세 군데로
 * 흩어지면 한쪽만 고치고 잊는 사고가 난다.
 *
 * <p><b>계획 존재와 무관하다</b> — 고정 일정은 그 주에 아직 계획(WeeklyPlan)이 없어도 화면에 보여야
 * 한다(그 주 요일 그리드 자체가 고정 일정을 깐다). 그래서 이 조립은 {@code weeklyPlanId}를 받지
 * 않고 {@code userId + weekStartDate}만으로 끝난다.
 */
@Component
public class FixedScheduleWeekAssembler {

    private static final int WEEK_SPAN_DAYS = 6; // 7일 주 → end = start + 6 (WeeklyPlanService와 동일 규약)

    private final FixedScheduleRepository fixedScheduleRepository;
    private final FixedScheduleWeekExceptionRepository weekExceptionRepository;

    public FixedScheduleWeekAssembler(FixedScheduleRepository fixedScheduleRepository,
                                      FixedScheduleWeekExceptionRepository weekExceptionRepository) {
        this.fixedScheduleRepository = fixedScheduleRepository;
        this.weekExceptionRepository = weekExceptionRepository;
    }

    /**
     * 그 주에 걸치는 고정 일정 전량(status 무관) + {@code activeThisWeek}.
     *
     * <p>{@code activeThisWeek = false}는 정본 설명 그대로 두 경우의 합이다 — ① 그 주
     * week-exception이 있다(PLAN-33, 그 주만 일시 해제) ② {@code status = INACTIVE}(영구 비활성,
     * 예: EXTERNAL 연동 해제 FIX-16). 둘 다 "이번 주엔 적용 안 됨"이라 고스트로 표시하고 재활성화
     * 액션(PLAN-34)을 붙일 대상이다 — 그래서 목록에서 빼지 않고 플래그만 내린다.
     *
     * <p><b>조회 2번으로 끝난다(N+1 없음)</b> — ① 날짜 겹침으로 후보 전량(1쿼리) ② 그 후보 id들 중
     * 이번 주 예외가 있는 id 집합(1쿼리, {@code IN} 배치). 고정 일정이 많아져도 쿼리 수는 늘지 않는다.
     *
     * <p><b>정렬은 Java에서 한다</b> — {@code weekday}는 DB에 문자열(VARCHAR)로 저장돼 SQL
     * {@code ORDER BY}가 알파벳순이 된다. 달력순(MON→SUN) → 같은 요일은 시작 시각 순으로 맞추려면
     * {@code Weekday.ordinal()} 기준 비교가 필요하다({@code AvailabilityService.toView}와 동일 관례,
     * 리뷰 Should-fix).
     */
    public List<FixedScheduleWeekView> assemble(UUID userId, LocalDate weekStartDate) {
        LocalDate weekEndDate = weekStartDate.plusDays(WEEK_SPAN_DAYS);

        List<FixedSchedule> candidates = fixedScheduleRepository
                .findOverlappingWeek(userId, weekStartDate, weekEndDate);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateIds = candidates.stream().map(FixedSchedule::getId).toList();
        Set<UUID> exceptedIds = Set.copyOf(
                weekExceptionRepository.findFixedScheduleIdsWithExceptionInWeek(weekStartDate, candidateIds));

        return candidates.stream()
                .sorted(Comparator.comparingInt((FixedSchedule fs) -> fs.getWeekday().ordinal())
                        .thenComparing(FixedSchedule::getStartTime))
                .map(fs -> {
                    boolean activeThisWeek = fs.getStatus() == FixedScheduleStatus.ACTIVE
                            && !exceptedIds.contains(fs.getId());
                    return FixedScheduleWeekView.of(fs, activeThisWeek);
                })
                .toList();
    }
}
