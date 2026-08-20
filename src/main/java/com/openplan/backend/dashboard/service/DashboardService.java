package com.openplan.backend.dashboard.service;

import com.openplan.backend.availability.domain.AvailabilityPattern;
import com.openplan.backend.availability.repository.AvailabilityPatternRepository;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.common.WeekRange;
import com.openplan.backend.dashboard.domain.PriorityActionType;
import com.openplan.backend.dashboard.domain.RiskType;
import com.openplan.backend.dashboard.dto.BusyWeekdayResponse;
import com.openplan.backend.dashboard.dto.DashboardQuery;
import com.openplan.backend.dashboard.dto.DashboardResponse;
import com.openplan.backend.dashboard.dto.ImpactedProjectResponse;
import com.openplan.backend.dashboard.dto.PriorityActionResponse;
import com.openplan.backend.dashboard.dto.RiskIssueResponse;
import com.openplan.backend.dashboard.dto.StatusBoardResponse;
import com.openplan.backend.dashboard.dto.TodayBoardItemResponse;
import com.openplan.backend.dashboard.dto.TodayBoardResponse;
import com.openplan.backend.dashboard.service.port.DashboardPlanReader;
import com.openplan.backend.dashboard.service.port.TodayBlockRow;
import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.ProjectAutoCloseEvaluator;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.repository.TaskRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 대시보드 조립 유스케이스 (ST-B2-15 — DASH-01~07 · RB-DASH-01/02). 라우트당 한 번의 읽기 조립이라
 * {@code readOnly} 트랜잭션으로 감싼다(단, PROJ-08 지연 평가는 별도 tx — {@link ProjectAutoCloseEvaluator}가
 * {@code REQUIRES_NEW}로 자체 처리, AC2).
 *
 * <p><b>엔진 포트 미도입 고지</b>: stories-be2-kr.md ST-B2-15는 {@code PriorityActionPort}·
 * {@code TodaySelectionPort}(BE-3 소유, ST-B3-04/05) 호출을 지시하지만, 그 스토리 정본이 외부 저장소로
 * 이동돼 있고(stories-be3-kr.md) 코드베이스에도 아직 해당 포트가 없다. 대신 이미 확정된 규칙문
 * (us-decisions-kr.md §3.2 Q3 · §4.5 · §5.1 · §5.2)을 이 서비스가 직접 구현한다 — 존재하지 않는 포트
 * 인터페이스를 지어내는 것보다 확정문을 그대로 옮기는 편이 안전하다는 판단(stats-dashboard-notes.md §1.2).
 * BE-3가 실제 엔진 포트를 만들면 이 로직을 그쪽 호출로 교체해야 한다.
 */
@Service
public class DashboardService {

    /** us-decisions-kr.md §5.1 (ASSUMPTION-D3) — 공용 "마감 임박" 정의. */
    private static final int DEADLINE_SOON_DAYS = 3;
    /** us-decisions-kr.md §5.2 (ASSUMPTION-D4) — 손 볼 요일 노출 컷. */
    private static final int BUSY_WEEKDAY_CUTOFF_PERCENT = 50;

    private final DashboardPlanReader planReader;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final AvailabilityPatternRepository availabilityPatternRepository;
    private final ProjectAutoCloseEvaluator projectAutoCloseEvaluator;
    private final UserClock clock;

    public DashboardService(DashboardPlanReader planReader, TaskRepository taskRepository,
                            ProjectRepository projectRepository, ExecutionLogRepository executionLogRepository,
                            AvailabilityPatternRepository availabilityPatternRepository,
                            ProjectAutoCloseEvaluator projectAutoCloseEvaluator, UserClock clock) {
        this.planReader = planReader;
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.executionLogRepository = executionLogRepository;
        this.availabilityPatternRepository = availabilityPatternRepository;
        this.projectAutoCloseEvaluator = projectAutoCloseEvaluator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID userId, DashboardQuery query) {
        // AC2 — 진입 시 PROJ-08 지연 평가(별도 tx, ST-B2-01-AC2 재사용). 이 tx가 readOnly라도
        // ProjectAutoCloseEvaluator 내부는 REQUIRES_NEW라 독립적으로 커밋된다.
        projectAutoCloseEvaluator.closeOverdue(userId);

        Instant referenceTime = clock.now();
        LocalDate today = clock.todayOf(userId);
        LocalDate baseDate = query.getBaseDate() != null ? query.getBaseDate() : today;
        Weekday weekStartDay = clock.weekStartDayOf(userId);
        WeekRange week = WeekRange.of(baseDate, weekStartDay);
        ZoneId zone = clock.zoneOf(userId);

        List<AvailabilityPattern> availabilities = availabilityPatternRepository.findByUserId(userId);
        List<TodayBlockRow> todayBlocks = planReader.todayBlocks(userId, today, zone);
        Map<String, Long> openIssues = planReader.openIssueCounts(userId, week.start());

        long fixedConflict = openIssues.getOrDefault("V2_FIXED_CONFLICT", 0L);
        long overlapConflict = openIssues.getOrDefault("V1_OVERLAP", 0L);
        long capacityExceeded = openIssues.getOrDefault("V3_CAPACITY_EXCEEDED", 0L);
        long outOfWbs = openIssues.getOrDefault("V5_OUT_OF_WBS", 0L);
        long unassigned = taskRepository.countUnassigned(userId);
        long deadlineSoon = taskRepository.countDeadlineSoon(userId, today, today.plusDays(DEADLINE_SOON_DAYS));
        long todayIncomplete = todayBlocks.stream()
                .filter(b -> b.taskBlock() && !b.completed() && !b.endAt().isAfter(referenceTime))
                .count();

        StatusBoardResponse statusBoard = buildStatusBoard(userId, week, availabilities);
        PriorityActionResponse priorityAction = resolvePriorityAction(
                fixedConflict, overlapConflict, unassigned, deadlineSoon, todayIncomplete, capacityExceeded, outOfWbs);
        List<RiskIssueResponse> riskIssues = buildRiskIssues(unassigned, outOfWbs, fixedConflict, deadlineSoon);
        TodayBoardResponse todayBoard = buildTodayBoard(todayBlocks);
        List<ImpactedProjectResponse> weeklyImpactProjects = buildImpactedProjects(userId, today);
        List<BusyWeekdayResponse> busyWeekdays = buildBusyWeekdays(userId, week, zone, weekStartDay, availabilities);

        return new DashboardResponse(statusBoard, priorityAction, riskIssues, todayBoard,
                weeklyImpactProjects, busyWeekdays);
    }

    /** DASH-01 — deltaMinutes = availableMinutes - plannedMinutes(양수=여유). 3단 판정 필드는 계약에 없다(StatusBoardResponse 참고). */
    private StatusBoardResponse buildStatusBoard(UUID userId, WeekRange week, List<AvailabilityPattern> availabilities) {
        int planned = planReader.totalPlannedMinutes(userId, week.start());
        int available = totalActiveMinutes(availabilities);
        return new StatusBoardResponse(week.start(), planned, available, available - planned);
    }

    /**
     * RB-DASH-01 최상위 1행동 (us-decisions-kr.md §3.2 Q3 확정 전순서). PriorityActionType 선언 순서 = 전순서.
     * 확정문 7유형을 모두 후보로 둔다 — 2위 겹침(V1)은 {@code RESOLVE_OVERLAP}으로 대응한다.
     * routePath는 확정문 표의 화면 코드를 그대로 인용한다(URL 스킴이 정본에 없어 지어내지 않음).
     */
    private PriorityActionResponse resolvePriorityAction(long fixedConflict, long overlapConflict, long unassigned,
                                                          long deadlineSoon, long todayIncomplete,
                                                          long capacityExceeded, long outOfWbs) {
        if (fixedConflict > 0) {
            return new PriorityActionResponse(PriorityActionType.RESOLVE_FIXED_CONFLICT.name(),
                    "규칙 V2에 의해 고정 일정과 겹치는 계획 블록이 있습니다.", "SCR-PLAN 검토 패널");
        }
        if (overlapConflict > 0) {
            return new PriorityActionResponse(PriorityActionType.RESOLVE_OVERLAP.name(),
                    "규칙 V1에 의해 서로 겹치는 계획 블록이 있습니다.", "SCR-PLAN 검토 패널");
        }
        if (unassigned > 0) {
            return new PriorityActionResponse(PriorityActionType.PLACE_UNASSIGNED.name(),
                    "아직 배치하지 않은 태스크가 있습니다.", "SCR-PLAN 미배치 패널");
        }
        if (deadlineSoon > 0) {
            return new PriorityActionResponse(PriorityActionType.HANDLE_DEADLINE.name(),
                    "마감이 " + DEADLINE_SOON_DAYS + "일 이내로 임박한 태스크가 있습니다.", "SCR-PLAN(해당 태스크 포커스)");
        }
        if (todayIncomplete > 0) {
            return new PriorityActionResponse(PriorityActionType.REPLACE_TODAY_INCOMPLETE.name(),
                    "오늘 종료 시각이 지났지만 완료되지 않은 태스크가 있습니다.", "SCR-PLAN");
        }
        if (capacityExceeded > 0) {
            return new PriorityActionResponse(PriorityActionType.RESOLVE_CAPACITY.name(),
                    "규칙 V3에 의해 가용 시간을 초과해 배치된 요일이 있습니다.", "OVL-REPLAN(작업 분산안 기본 선택)");
        }
        if (outOfWbs > 0) {
            return new PriorityActionResponse(PriorityActionType.FIX_OUT_OF_WBS.name(),
                    "규칙 V5에 의해 WBS 범위를 벗어나 배치된 태스크가 있습니다.", "SCR-PROJ-WS 계획(WBS) 탭");
        }
        return null; // 후보 0건 — FE 긍정 상태 카드(us-decisions-kr.md §3.3)
    }

    /**
     * DASH-03/04 위험 목록. 순서는 openapi {@code riskType} enum 선언 순(=RiskType 선언 순) — Q3 전순서와
     * 별개다(단일 행동 선정과 목록 노출은 다른 관심사). count=0인 유형은 목록에서 제외한다.
     */
    private List<RiskIssueResponse> buildRiskIssues(long unassigned, long outOfWbs, long fixedConflict, long deadlineSoon) {
        List<RiskIssueResponse> risks = new ArrayList<>();
        if (unassigned > 0) {
            risks.add(new RiskIssueResponse(RiskType.UNASSIGNED_TASKS.name(), (int) unassigned,
                    "배치하지 않은 태스크가 " + unassigned + "건 있습니다.", "SCR-PLAN 미배치 패널"));
        }
        if (outOfWbs > 0) {
            risks.add(new RiskIssueResponse(RiskType.OUT_OF_WBS.name(), (int) outOfWbs,
                    "규칙 V5에 의해 WBS 범위를 벗어난 배치가 " + outOfWbs + "건 있습니다.", "SCR-PROJ-WS 계획(WBS) 탭"));
        }
        if (fixedConflict > 0) {
            risks.add(new RiskIssueResponse(RiskType.FIXED_CONFLICT.name(), (int) fixedConflict,
                    "규칙 V2에 의해 고정 일정과 겹치는 배치가 " + fixedConflict + "건 있습니다.", "SCR-PLAN 검토 패널"));
        }
        if (deadlineSoon > 0) {
            risks.add(new RiskIssueResponse(RiskType.DEADLINE_SOON.name(), (int) deadlineSoon,
                    "마감이 " + DEADLINE_SOON_DAYS + "일 이내로 임박한 태스크가 " + deadlineSoon + "건 있습니다.",
                    "SCR-PLAN(해당 태스크 포커스)"));
        }
        return risks;
    }

    /**
     * DASH-05/RB-DASH-02. {@code selectionRank}는 전부 null(SS-02 타이브레이크 미정 — stats-dashboard-notes.md
     * §1.2). {@code remainingMinutes}는 "오늘 남은 소요"로 해석해 미완료 항목의 예상시간(없으면 블록 길이)
     * 합으로 계산한다(정본에 산식 명시 없음 — 낮은 위험의 서식 선택).
     */
    private TodayBoardResponse buildTodayBoard(List<TodayBlockRow> blocks) {
        List<TodayBoardItemResponse> items = blocks.stream()
                .map(b -> new TodayBoardItemResponse(b.planBlockId(), b.taskId(), b.title(),
                        b.startAt(), b.endAt(), b.estimatedMinutes(), b.completed(), null))
                .toList(); // JdbcDashboardPlanReader가 이미 start_at ASC로 정렬해 반환

        int remaining = blocks.stream()
                .filter(b -> !b.completed())
                .mapToInt(b -> b.estimatedMinutes() != null
                        ? b.estimatedMinutes()
                        : (int) Duration.between(b.startAt(), b.endAt()).toMinutes())
                .sum();

        return new TodayBoardResponse(items, remaining);
    }

    /**
     * DASH-06 — badges가 1개 이상인 프로젝트만 포함한다. N+1 회피를 위해 unassigned/실적 데이터를 배치로
     * 한 번씩만 조회한 뒤 프로젝트 목록을 한 번 순회한다.
     */
    private List<ImpactedProjectResponse> buildImpactedProjects(UUID userId, LocalDate today) {
        List<Project> projects = projectRepository
                .findByUserIdAndStatusIn(userId, List.of(ProjectStatus.IN_PROGRESS), Pageable.unpaged())
                .getContent();
        if (projects.isEmpty()) {
            return List.of();
        }

        Set<UUID> unassignedProjectIds = new HashSet<>(taskRepository.findProjectIdsWithUnassignedTasks(userId));
        Set<UUID> projectIds = new HashSet<>();
        for (Project p : projects) {
            projectIds.add(p.getId());
        }
        Map<UUID, int[]> actualVsEstimated = actualVsEstimatedByProject(userId, projectIds);

        List<ImpactedProjectResponse> result = new ArrayList<>();
        for (Project p : projects) {
            List<String> badges = new ArrayList<>();
            if (isDeadlineSoon(p.getDueDate(), today)) {
                badges.add("DEADLINE_SOON");
            }
            if (unassignedProjectIds.contains(p.getId())) {
                badges.add("HAS_UNASSIGNED");
            }
            int[] agg = actualVsEstimated.getOrDefault(p.getId(), new int[]{0, 0});
            if (agg[1] > agg[0]) {
                badges.add("ACTUAL_OVERRUN");
            }
            if (!badges.isEmpty()) {
                result.add(new ImpactedProjectResponse(p.getId(), p.getName(), badges));
            }
        }
        return result;
    }

    private boolean isDeadlineSoon(LocalDate dueDate, LocalDate today) {
        if (dueDate == null) {
            return false;
        }
        long daysUntil = ChronoUnit.DAYS.between(today, dueDate);
        return daysUntil >= 0 && daysUntil <= DEADLINE_SOON_DAYS;
    }

    /**
     * 프로젝트별 [예상 합, 실제 합](전체 이력 기준 — DASH-06엔 기간 파라미터가 없어 채택). 태스크는
     * 프로젝트당 1회, 실제시간은 로그 합산 — stats 패키지 {@code StatsService}와 동일한 원칙
     * ("execution_logs 예상시간 스냅샷 부재" 한계도 동일하게 적용됨, stats-dashboard-notes.md §2.2).
     */
    private Map<UUID, int[]> actualVsEstimatedByProject(UUID userId, Set<UUID> projectIds) {
        List<ExecutionLog> logs = executionLogRepository.findByUserId(userId);
        if (logs.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> actualByTask = new HashMap<>();
        for (ExecutionLog log : logs) {
            Integer actual = log.getActualMinutes();
            actualByTask.merge(log.getTaskId(), actual == null ? 0 : actual, Integer::sum);
        }
        Map<UUID, Task> tasksById = new HashMap<>();
        for (Task t : taskRepository.findAllById(actualByTask.keySet())) {
            tasksById.put(t.getId(), t);
        }

        Map<UUID, int[]> byProject = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : actualByTask.entrySet()) {
            Task task = tasksById.get(entry.getKey());
            if (task == null || !projectIds.contains(task.getProjectId())) {
                continue;
            }
            int[] agg = byProject.computeIfAbsent(task.getProjectId(), k -> new int[2]);
            agg[0] += task.getEstimatedMinutes() == null ? 0 : task.getEstimatedMinutes();
            agg[1] += entry.getValue();
        }
        return byProject;
    }

    /**
     * DASH-07 — us-decisions-kr.md §5.2. 잔여율 최소 요일 1건(동률이면 주 시작 요일부터 앞선 요일),
     * 전 요일 잔여율 ≥50%면 빈 배열("손 볼 요일 없음").
     */
    private List<BusyWeekdayResponse> buildBusyWeekdays(UUID userId, WeekRange week, ZoneId zone,
                                                         Weekday weekStartDay, List<AvailabilityPattern> availabilities) {
        Map<DayOfWeek, Integer> plannedByDow = planReader.plannedMinutesByWeekday(userId, week.start(), week.end(), zone);
        Map<Weekday, Integer> availableByWeekday = new HashMap<>();
        for (AvailabilityPattern a : availabilities) {
            if (!a.isActive()) {
                continue;
            }
            int minutes = (int) Duration.between(a.getStartTime(), a.getEndTime()).toMinutes();
            availableByWeekday.merge(a.getWeekday(), minutes, Integer::sum);
        }

        Weekday bestWeekday = null;
        int bestPercent = -1;
        int bestOrder = Integer.MAX_VALUE;
        for (Weekday weekday : Weekday.values()) {
            int available = availableByWeekday.getOrDefault(weekday, 0);
            if (available == 0) {
                continue; // D-5.2 — available=0 요일 제외
            }
            DayOfWeek dow = DayOfWeek.of(weekday.ordinal() + 1); // Weekday 선언 순(MON..SUN) = DayOfWeek.getValue()-1 전제
            int planned = plannedByDow.getOrDefault(dow, 0);
            int remaining = Math.max(0, available - planned);
            int remainingPercent = (remaining * 100) / available; // 정수 내림
            int order = Math.floorMod(weekday.ordinal() - weekStartDay.ordinal(), 7);

            boolean better = bestWeekday == null || remainingPercent < bestPercent
                    || (remainingPercent == bestPercent && order < bestOrder);
            if (better) {
                bestWeekday = weekday;
                bestPercent = remainingPercent;
                bestOrder = order;
            }
        }

        if (bestWeekday == null || bestPercent >= BUSY_WEEKDAY_CUTOFF_PERCENT) {
            return List.of();
        }
        return List.of(new BusyWeekdayResponse(bestWeekday.name(), bestPercent));
    }

    private int totalActiveMinutes(List<AvailabilityPattern> availabilities) {
        int total = 0;
        for (AvailabilityPattern a : availabilities) {
            if (a.isActive()) {
                total += (int) Duration.between(a.getStartTime(), a.getEndTime()).toMinutes();
            }
        }
        return total;
    }
}
