package com.openplan.backend.dashboard.service.port;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 대시보드 조립(ST-B2-15)이 필요로 하는 PLAN 도메인(weekly_plans·plan_blocks) 읽기 경계 계약.
 *
 * <p>dashboard 패키지는 weeklyplan 패키지를 직접 import하지 않는다(현재 PR #26 리뷰 대기 중이라 소유
 * 겹침 0 규칙 — team-lead 지시). {@code executionlog.PlanBlockOwnershipChecker}·
 * {@code project.WeeklyPlanTotalsRecalculator}와 같은 선례를 따라 이 port + JdbcTemplate 구현체로만
 * 접촉한다. PLAN 도메인이 이 데이터의 정식 조회 API를 갖추면 이 port 구현체를 그쪽 호출로 교체한다
 * (인터페이스 불변, project 패키지 선례와 동일한 이관 전제).
 */
public interface DashboardPlanReader {

    /** 해당 주(user_id, week_start_date)의 총 배치 분 — 행 없으면(주간 계획 미생성) 0. */
    int totalPlannedMinutes(UUID userId, LocalDate weekStartDate);

    /**
     * 그 주의 요일별 배치 분 합계(zone 기준 로컬 요일) — DASH-07 분자. 배치 없는 요일은 맵에서 생략된다
     * (호출 측이 {@code getOrDefault(day, 0)}로 채운다).
     */
    Map<DayOfWeek, Integer> plannedMinutesByWeekday(UUID userId, LocalDate weekStart, LocalDate weekEnd, ZoneId zone);

    /** zone 기준 today에 시작하는 블록 전부(오늘 실행 보드 원재료, RB-DASH-02/SS-02). */
    List<TodayBlockRow> todayBlocks(UUID userId, LocalDate today, ZoneId zone);

    /**
     * 이번 주(user_id, week_start_date) OPEN 검증 이슈를 규칙 ID별로 센다 — {@code rule_engine.model.RuleId}
     * 이름과 1:1(V1_OVERLAP..V7_BUFFER_SHORTAGE). 저장 경로가 아직 없어(엔진은 순수 함수, C-2) 현재는 항상
     * 빈 맵을 반환한다 — 실제로 비어 있는 것이지 이 메서드가 값을 지어내지 않는다
     * (stats-dashboard-notes.md §2.3).
     */
    Map<String, Long> openIssueCounts(UUID userId, LocalDate weekStartDate);
}
