package com.openplan.backend.stats.service;

import com.openplan.backend.category.domain.TaskCategory;
import com.openplan.backend.category.repository.TaskCategoryRepository;
import com.openplan.backend.common.WeekRange;
import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.domain.ExecutionResult;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.stats.domain.DeviationGroupBy;
import com.openplan.backend.stats.domain.StatsPeriod;
import com.openplan.backend.stats.domain.TimeSlot;
import com.openplan.backend.stats.dto.CorrectionProposalQuery;
import com.openplan.backend.stats.dto.CorrectionProposalResponse;
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
 * 수행 통계 유스케이스 (ST-B2-16 — RB-STAT-01/02/03 · SS-10/11/12). 보정 제안(SS-11)은 산출식 파라미터가
 * 정본에 없어 오래 보류돼 있었고, W3 게이트에서 ASSUMPTION-CP1~CP5로 확정한 뒤 편입했다
 * ({@link CorrectionProposalPolicy} 참고 — 재결정 시 상수·문구·골든·openapi를 한 커밋에 함께 갱신).
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

    /**
     * 예상 시간 보정 제안 (SS-11 / RB-STAT-02). 사용자가 태스크 폼에 입력 중인 예상값을, 같은 스코프의
     * 과거 편차율만큼 조정해 제안한다. <b>읽기 전용·부작용 0</b>이며 자동 적용은 없다(C-2/P2).
     *
     * <p><b>편차율은 {@link #deviations}와 같은 산법이다</b> — 태스크별 실제시간 합산(로그 여러 건 합산) +
     * 예상시간은 태스크당 1회, rate = (Σactual − Σestimated) × 100 / Σestimated. US 전제가 "편차 분석이
     * 완료됨"(RB-STAT-01 소비)이라, 사용자가 통계 화면에서 보는 수치와 제안의 근거가 어긋나면 안 된다.
     * 그래서 스냅샷 예상시간 전환도 이 메서드가 단독으로 앞서가지 않는다(클래스 javadoc의 알려진 한계 승계).
     *
     * <p><b>집계 창은 전체 이력</b>(ASSUMPTION-CP1) — 이 라우트에 기간 파라미터가 없다. 결과가 시계에
     * 의존하지 않으므로 형제 3본보다 결정성이 강하다(같은 데이터면 언제 호출해도 같은 응답).
     *
     * <p>제안 불가 3사유는 모두 {@code null} 반환(호출자가 data 생략): ① 표본 부족(&lt; 3)
     * ② Σestimated = 0(편차율 정의 불가) ③ estimatedMinutes 미제공(조정할 대상이 없음).
     * 셋 다 오류가 아니라 정상 응답이다.
     *
     * <p>참조 ID는 소유 검증한다 — 부재·타인 categoryId/projectId는 404(구분 불가). 조용히 null로 삼키면
     * FE가 "오타 UUID"와 "이력 부족"을 구분하지 못한다.
     */
    @Transactional(readOnly = true)
    public CorrectionProposalResponse correctionProposal(UUID userId, CorrectionProposalQuery query) {
        validator.validateEstimatedMinutes(query.getEstimatedMinutes()); // 422 (step) — null은 통과
        requireOwnedReferences(userId, query);

        Integer estimatedMinutes = query.getEstimatedMinutes();
        if (estimatedMinutes == null) {
            return null; // ③ 조정할 대상이 없다
        }

        CorrectionProposalPolicy.Scope scope = resolveScope(query);

        // 전체 이력을 한 번 읽고(CP-1) 태스크도 한 번만 올린 뒤, 스코프·근거 판정을 같은 맵으로 처리한다 —
        // 로그에 카테고리·프로젝트 컬럼이 없어 둘 다 태스크를 거쳐야 하므로 한 번에 묶는 편이 맞다.
        List<ExecutionLog> all = executionLogRepository.findByUserId(userId);
        Map<UUID, Task> tasksById = loadTasks(all);

        List<ExecutionLog> measurable = measurableLogs(inScope(all, tasksById, query, scope), tasksById);
        int estimatedSum = distinctTaskEstimatedSum(measurable, tasksById);
        if (estimatedSum == 0) {
            return null; // ② 비교 기준이 없다 — 편차율을 정의할 수 없다
        }
        int actualSum = measurable.stream().mapToInt(l -> nz(l.getActualMinutes())).sum();
        double deviationRate = (actualSum - estimatedSum) * 100.0 / estimatedSum;

        // 표본 하한(①)은 Policy가 판정한다 — 여기서 미리 자르면 같은 규칙이 두 곳에 생긴다.
        return CorrectionProposalPolicy.evaluate(estimatedMinutes, deviationRate, measurable.size(), scope);
    }

    /**
     * 편차 계산의 <b>근거로 쓸 수 있는</b> 로그만 남긴다. 두 조건이며, 둘 다 "이 기록이 소요 시간의
     * 증거가 되는가"라는 같은 질문이다.
     *
     * <p><b>① 소속 태스크에 예상시간이 있어야 한다</b> — 없으면 실제시간은 분자에 더해지는데 예상시간은
     * 0으로 합산돼 분모에 기여하지 않아 편차율이 부풀려진다. 예상 없는 태스크에 180분이 기록돼 있고
     * 예상 60분짜리가 정확히 60분 걸렸다면, 거르지 않을 때 r = (240−60)/60 = +300%가 나온다 —
     * 비교 가능한 유일한 태스크의 편차는 0%인데도 4배를 제안하게 된다. 스냅샷 컬럼 마이그레이션이 이미
     * 같은 요구를 적어 두었다: <i>"소비처(통계)는 NULL 을 편차 계산에서 제외해야 한다"</i>.
     * [Source: V202608161500__be1_execution_log_estimated_minutes.sql]
     *
     * <p><b>② 중단({@code ABORTED})이 아니어야 한다</b>(User 확정 2026-08-27) — 포기한 시도는 "그 일이 더
     * 짧게 걸린다"는 증거가 아니라 <b>끝내지 못했다</b>는 기록이다. 예상 60분짜리를 5분 하고 세 번 포기하면
     * r = −75%가 되어 "60분을 15분으로 줄이라"는 정반대 처방이 나간다. {@code DELAYED}는 남긴다 —
     * "늦어졌다"는 실제로 더 걸렸다는 정직한 증거이고, 예상을 늘려야 한다는 가장 중요한 신호다.
     *
     * <p>결과적으로 {@code sampleSize}도 "근거로 쓰인 이력 건수"가 된다 — 제시하는 숫자가 실제 계산에
     * 쓰인 표본과 일치해야 사용자가 검산할 수 있다(P2).
     *
     * <p>※ {@code deviations}·{@code summaries}는 두 필터를 <b>모두 두지 않는다</b>. 그쪽은 수치를
     * <b>보여줄</b> 뿐이라 총량이 사실대로 드러나는 편이 맞지만, 이 라우트는 그 수치를 사용자 입력값으로
     * <b>처방</b>한다. 스토리의 F-0("deviations와 같은 산법")은 <b>집계 방식</b>(태스크당 예상 1회·실제 합산)을
     * 가리키는 것이고, 어떤 기록을 근거로 삼을지는 처방하는 쪽이 더 좁게 잡는다.
     */
    private static List<ExecutionLog> measurableLogs(List<ExecutionLog> logs, Map<UUID, Task> tasksById) {
        return logs.stream()
                .filter(log -> log.getResult() != ExecutionResult.ABORTED) // ② 포기는 소요 시간의 증거가 아니다
                .filter(log -> {
                    Task task = tasksById.get(log.getTaskId());
                    return task != null && task.getEstimatedMinutes() != null; // ① 비교 기준이 있어야 한다
                })
                .toList();
    }

    /** 스코프 우선순위 — categoryId &gt; projectId &gt; 전체(ASSUMPTION-CP3). 묵시 폴백은 없다. */
    private static CorrectionProposalPolicy.Scope resolveScope(CorrectionProposalQuery query) {
        if (query.getCategoryId() != null) {
            return CorrectionProposalPolicy.Scope.CATEGORY;
        }
        return query.getProjectId() != null
                ? CorrectionProposalPolicy.Scope.PROJECT
                : CorrectionProposalPolicy.Scope.ALL;
    }

    /**
     * 스코프에 해당하는 로그만 추린다. 로그에 카테고리·프로젝트 컬럼이 없어 태스크를 거쳐야 한다
     * (태스크를 통해서만 분류된다 — {@link #deviations}와 동일 구조).
     *
     * <p><b>선택된 스코프의 이력이 부족해도 다른 스코프로 내려가지 않는다</b> — 폴백 사다리는 정본 무앵커이고,
     * basis가 말하는 스코프와 실제 계산 스코프가 어긋나면 사용자가 검산할 수 없다(투명성 훼손).
     *
     * <p><b>알려진 한계</b>: 스코프가 좁아도 사용자의 전 이력을 읽어 메모리에서 거른다. 스코프 조건을
     * 쿼리로 내리면 읽는 양이 줄지만 저장소 표면이 늘어나므로(이 스토리는 신규 쿼리 0이 요구사항)
     * 이번 슬라이스에서는 하지 않았다. {@code ExecutionLogRepository#findByUserId}의 "MVP 규모" 전제가
     * 깨지면 — 이력이 커지거나 이 라우트가 입력 도중 자주 호출되면 — 스코프 조건을 내린 쿼리가 필요하다.
     */
    private static List<ExecutionLog> inScope(List<ExecutionLog> logs, Map<UUID, Task> tasksById,
                                              CorrectionProposalQuery query,
                                              CorrectionProposalPolicy.Scope scope) {
        if (scope == CorrectionProposalPolicy.Scope.ALL) {
            return logs;
        }
        return logs.stream()
                .filter(log -> {
                    Task task = tasksById.get(log.getTaskId());
                    if (task == null) {
                        return false; // 삭제된 태스크 방어(deviations와 동일)
                    }
                    return scope == CorrectionProposalPolicy.Scope.CATEGORY
                            ? query.getCategoryId().equals(task.getCategoryId())
                            : query.getProjectId().equals(task.getProjectId());
                })
                .toList();
    }

    /** 제공된 참조 ID의 소유 검증 (부재·타인 → 404 E-COM-004, 구분 불가). 미제공은 검증 대상이 아니다. */
    private void requireOwnedReferences(UUID userId, CorrectionProposalQuery query) {
        if (query.getCategoryId() != null
                && !taskCategoryRepository.existsByIdAndUserId(query.getCategoryId(), userId)) {
            throw new OpenPlanException(ErrorCode.E_COM_004);
        }
        if (query.getProjectId() != null
                && projectRepository.findByIdAndUserId(query.getProjectId(), userId).isEmpty()) {
            throw new OpenPlanException(ErrorCode.E_COM_004);
        }
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
