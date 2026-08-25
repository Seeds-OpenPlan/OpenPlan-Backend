package com.openplan.backend.stats.service;

import com.openplan.backend.category.domain.TaskCategory;
import com.openplan.backend.category.repository.TaskCategoryRepository;
import com.openplan.backend.common.WeekRange;
import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.domain.ExecutionResult;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.stats.domain.DeviationGroupBy;
import com.openplan.backend.stats.domain.StatsPeriod;
import com.openplan.backend.stats.domain.TimeSlot;
import com.openplan.backend.stats.dto.DeviationReportResponse;
import com.openplan.backend.stats.dto.DeviationRowResponse;
import com.openplan.backend.stats.dto.DeviationsQuery;
import com.openplan.backend.stats.dto.StatsSummaryQuery;
import com.openplan.backend.stats.dto.StatsSummaryResponse;
import com.openplan.backend.stats.dto.TimePatternReportResponse;
import com.openplan.backend.stats.dto.TimePatternsQuery;
import com.openplan.backend.stats.dto.TimeSlotResponse;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 수행 통계 3종 유스케이스 (ST-B2-16 — RB-STAT-01/03 · SS-10/12). {@code /stats/correction-proposals}는
 * 산출식이 정본에 없어(SS-11) 여기 포함하지 않는다 — openapi {@code x-implementation-status} 그대로 유지.
 *
 * <p><b>알려진 한계(리드 확인 대상, stats-dashboard-notes.md §2.2)</b>: {@code execution_logs}에
 * ST-B2-14 AC②가 요구하는 "기록 시점 예상시간 스냅샷" 컬럼이 없다. 이 서비스는 부득이
 * {@link Task#getEstimatedMinutes()}(현재값)를 프록시로 쓴다 — 기록 이후 태스크 예상시간이 편집되면
 * 과거 편차 수치가 재계산 시 달라질 수 있다(P1 "동일 이력=동일 수치"와 엄밀히는 어긋남).
 *
 * <p><b>집계 단위</b>: 이력(ExecutionLog) 건수 기준. 한 태스크에 로그가 여러 건이면 실제시간은 합산하되
 * 예상시간은 태스크당 1회만 반영한다(태스크 속성이라 로그 건수만큼 중복 합산하면 부풀려진다). 완료율(예:
 * time-patterns)은 태스크가 아니라 <b>로그 건수</b> 기준이다 — "몇 번 수행 중 몇 번을 계획대로 끝냈는가"가
 * TimePatternReport·StatsSummary 양쪽에서 자연스러운 해석이라 통일했다(정본에 명시 없어 채택, 낮은 리스크).
 */
@Service
public class StatsService {

    private final ExecutionLogRepository executionLogRepository;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TaskCategoryRepository taskCategoryRepository;
    private final UserClock clock;
    private final StatsQueryValidator validator;

    public StatsService(ExecutionLogRepository executionLogRepository, TaskRepository taskRepository,
                        ProjectRepository projectRepository, TaskCategoryRepository taskCategoryRepository,
                        UserClock clock, StatsQueryValidator validator) {
        this.executionLogRepository = executionLogRepository;
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.taskCategoryRepository = taskCategoryRepository;
        this.clock = clock;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public StatsSummaryResponse summaries(UUID userId, StatsSummaryQuery query) {
        StatsPeriod period = validator.resolvePeriod(query.getPeriod());
        LocalDate baseDate = resolveBaseDate(userId, query.getBaseDate());
        LocalDate[] range = resolveRange(userId, period, baseDate);
        List<ExecutionLog> logs = fetchLogs(userId, range[0], range[1]);

        if (logs.isEmpty()) {
            return new StatsSummaryResponse(period.name(), range[0], range[1], 0, 0, null, null, true);
        }

        Map<UUID, Task> tasksById = loadTasks(logs);
        int totalEstimated = distinctTaskEstimatedSum(logs, tasksById);
        int totalActual = logs.stream().mapToInt(l -> nz(l.getActualMinutes())).sum();
        long completed = logs.stream().filter(l -> l.getResult() == ExecutionResult.COMPLETED).count();

        Double completionRate = rate(completed, logs.size());
        Double varianceRate = totalEstimated == 0 ? null : ((totalActual - totalEstimated) * 100.0) / totalEstimated;

        return new StatsSummaryResponse(period.name(), range[0], range[1],
                totalEstimated, totalActual, completionRate, varianceRate, false);
    }

    @Transactional(readOnly = true)
    public DeviationReportResponse deviations(UUID userId, DeviationsQuery query) {
        StatsPeriod period = validator.resolvePeriod(query.getPeriod());
        DeviationGroupBy groupBy = validator.resolveGroupBy(query.getGroupBy());
        LocalDate baseDate = resolveBaseDate(userId, query.getBaseDate());
        LocalDate[] range = resolveRange(userId, period, baseDate);
        List<ExecutionLog> logs = fetchLogs(userId, range[0], range[1]);

        if (logs.isEmpty()) {
            return new DeviationReportResponse(groupBy.name(), true, List.of());
        }

        Map<UUID, Task> tasksById = loadTasks(logs);

        // 태스크별 실제시간 합산(중복 로그 합산) + 예상시간(태스크당 1회)을 먼저 모은 뒤 그룹으로 접는다.
        Map<UUID, Integer> actualByTask = new HashMap<>();
        for (ExecutionLog log : logs) {
            actualByTask.merge(log.getTaskId(), nz(log.getActualMinutes()), Integer::sum);
        }

        record Agg(int estimated, int actual) {
            Agg plus(int e, int a) {
                return new Agg(estimated + e, actual + a);
            }
        }
        Map<UUID, Agg> byGroup = new LinkedHashMap<>(); // groupId=null 허용 키(카테고리 미지정)

        for (Map.Entry<UUID, Integer> entry : actualByTask.entrySet()) {
            Task task = tasksById.get(entry.getKey());
            if (task == null) {
                continue; // 삭제된 태스크(FK ON DELETE CASCADE로 로그도 같이 삭제되므로 이론상 도달 안 함) 방어
            }
            UUID groupId = groupBy == DeviationGroupBy.PROJECT ? task.getProjectId() : task.getCategoryId();
            // 🔴 임시 — 태스크의 "현재" 예상 시간이라 태스크를 편집하면 과거 편차가 소급해 흔들린다.
            // 정본이 요구하는 값은 execution_logs.estimated_minutes 스냅샷(ST-B2-14 AC②)이며 컬럼은
            // V202608161500 으로 신설·기록 배선까지 끝났다. 그런데 여기서 바로 갈아끼우지 않는 이유:
            //   ⑴ SS-10 이 "편차 산술 집계"까지만 정의하고, 한 태스크에 로그가 여러 건일 때 어느 스냅샷을
            //      쓰는지(최초/최신)를 정하지 않았다 — 지금 고르면 미비준 가정을 코드에 박는 셈이다.
            //   ⑵ stories-be2-kr.md:173 이 이 집계를 DeviationAnalysisPort(ST-B3-08, 엔진 레인) 소관으로
            //      지정했다. 옮길 때 함께 확정하는 편이 맞다.
            // 스냅샷은 이미 쌓이고 있으므로 전환 시 소급 적용이 가능하다(기록은 놓치면 복원 불가라 선행).
            int estimated = nz(task.getEstimatedMinutes());
            Agg prev = byGroup.getOrDefault(groupId, new Agg(0, 0));
            byGroup.put(groupId, prev.plus(estimated, entry.getValue()));
        }

        Map<UUID, String> groupNames = resolveGroupNames(groupBy, byGroup.keySet());

        List<DeviationRowResponse> rows = new ArrayList<>();
        for (Map.Entry<UUID, Agg> e : byGroup.entrySet()) {
            int deviationMinutes = e.getValue().actual() - e.getValue().estimated();
            Double deviationRate = e.getValue().estimated() == 0 ? null
                    : (deviationMinutes * 100.0) / e.getValue().estimated();
            rows.add(new DeviationRowResponse(e.getKey(), groupNames.get(e.getKey()),
                    e.getValue().estimated(), e.getValue().actual(), deviationMinutes, deviationRate));
        }
        // 결정적 정렬(P1) — 이름 오름차순, "없음"(groupId=null) 그룹은 항상 마지막(정본에 순서 지정 없음 — 최소 위험한 서식 선택).
        rows.sort(Comparator.comparing(DeviationRowResponse::groupId, Comparator.nullsLast(Comparator.naturalOrder())));

        return new DeviationReportResponse(groupBy.name(), false, rows);
    }

    @Transactional(readOnly = true)
    public TimePatternReportResponse timePatterns(UUID userId, TimePatternsQuery query) {
        StatsPeriod period = query.getPeriod() == null || query.getPeriod().isBlank()
                ? StatsPeriod.WEEKLY // 정본에 기본값 없음 — enum 첫 값 채택(§ 클래스 javadoc 대응 DTO 참고)
                : validator.resolvePeriod(query.getPeriod());
        LocalDate baseDate = resolveBaseDate(userId, query.getBaseDate());
        LocalDate[] range = resolveRange(userId, period, baseDate);
        List<ExecutionLog> logs = fetchLogs(userId, range[0], range[1]);

        if (logs.isEmpty()) {
            return new TimePatternReportResponse(true, List.of());
        }

        ZoneId zone = clock.zoneOf(userId);
        Map<TimeSlot, int[]> bySlot = new EnumMap<>(TimeSlot.class); // [totalCount, completedCount]
        for (TimeSlot slot : TimeSlot.values()) {
            bySlot.put(slot, new int[2]);
        }
        for (ExecutionLog log : logs) {
            TimeSlot slot = TimeSlot.fromLocalTime(log.getStartedAt().atZone(zone).toLocalTime());
            int[] counts = bySlot.get(slot);
            counts[0]++;
            if (log.getResult() == ExecutionResult.COMPLETED) {
                counts[1]++;
            }
        }

        List<TimeSlotResponse> slots = new ArrayList<>();
        for (TimeSlot slot : TimeSlot.values()) {
            int[] counts = bySlot.get(slot);
            Double completionRate = rate(counts[1], counts[0]);
            slots.add(new TimeSlotResponse(slot.name(), counts[0], counts[1], completionRate));
        }
        return new TimePatternReportResponse(false, slots);
    }

    private LocalDate resolveBaseDate(UUID userId, LocalDate baseDate) {
        return baseDate != null ? baseDate : clock.todayOf(userId);
    }

    /** WEEKLY = 사용자 주 시작 요일 기준 7일({@link WeekRange}), MONTHLY = 달력 월(1일~말일). */
    private LocalDate[] resolveRange(UUID userId, StatsPeriod period, LocalDate baseDate) {
        if (period == StatsPeriod.WEEKLY) {
            WeekRange weekRange = WeekRange.of(baseDate, clock.weekStartDayOf(userId));
            return new LocalDate[]{weekRange.start(), weekRange.end()};
        }
        LocalDate start = baseDate.withDayOfMonth(1);
        LocalDate end = baseDate.withDayOfMonth(baseDate.lengthOfMonth());
        return new LocalDate[]{start, end};
    }

    /** [start, end] 양끝 포함 로컬 날짜 범위를 사용자 zone 기준 Instant 반개구간으로 변환해 조회한다. */
    private List<ExecutionLog> fetchLogs(UUID userId, LocalDate start, LocalDate end) {
        ZoneId zone = clock.zoneOf(userId);
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(zone).toInstant();
        return executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(userId, from, to);
    }

    private Map<UUID, Task> loadTasks(List<ExecutionLog> logs) {
        // 로그의 taskId는 전부 조회 사용자 소유(기록 시점에 소유 검증 완료 — executionlog.service.ExecutionLogService).
        List<UUID> taskIds = logs.stream().map(ExecutionLog::getTaskId).distinct().toList();
        Map<UUID, Task> byId = new HashMap<>();
        for (Task task : taskRepository.findAllById(taskIds)) {
            byId.put(task.getId(), task);
        }
        return byId;
    }

    private int distinctTaskEstimatedSum(List<ExecutionLog> logs, Map<UUID, Task> tasksById) {
        return logs.stream().map(ExecutionLog::getTaskId).distinct()
                .map(tasksById::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(t -> nz(t.getEstimatedMinutes()))
                .sum();
    }

    private Map<UUID, String> resolveGroupNames(DeviationGroupBy groupBy, java.util.Set<UUID> groupIds) {
        Map<UUID, String> names = new HashMap<>();
        List<UUID> nonNullIds = groupIds.stream().filter(java.util.Objects::nonNull).toList();
        if (groupBy == DeviationGroupBy.PROJECT) {
            // 프로젝트는 태스크 소유 체인상 전부 조회 사용자 소유(task.projectId가 이미 그 사용자 프로젝트).
            for (Project p : projectRepository.findAllById(nonNullIds)) {
                names.put(p.getId(), p.getName());
            }
        } else {
            for (TaskCategory c : taskCategoryRepository.findAllById(nonNullIds)) {
                names.put(c.getId(), c.getName());
            }
            names.put(null, "없음"); // openapi 설명 — 카테고리 null 그룹은 '없음'
        }
        return names;
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (numerator * 100.0) / denominator;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
