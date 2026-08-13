package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.availability.domain.AvailabilityPattern;
import com.openplan.backend.availability.repository.AvailabilityPatternRepository;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.TaskFacts;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.weeklyplan.dto.ValidateWeeklyPlanRequest.VirtualTaskEdit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 검증 입력 {@link PlanSnapshot} 조립 (ST-B2-09의 핵심 책임 — rule-engine-contract §3.1).
 *
 * <p>블록은 호출자가 넘긴다(저장 초안 또는 dry-run 가상 배치). 나머지 재료를 도메인별로 모은다:
 * <ul>
 *   <li>가용시간 7행 — availability 도메인</li>
 *   <li>고정일정 — 그 주 유효분만: {@code status=ACTIVE} + 주차예외({@code fixed_schedule_week_exceptions}) 안티조인.
 *       고정일정 도메인 엔티티가 이 브랜치에 없어 baseline 테이블을 JdbcTemplate로 읽는다(global {@code JdbcUserClock}과
 *       동일한 "타 도메인 잠정 SQL 접촉" 관례). 기간(start_date/end_date)은 {@link FixedWindow}에 그대로 실어 엔진에 맡긴다.</li>
 *   <li>태스크 사실 — 블록이 가리키는 태스크. dry-run은 {@code virtualTaskEdits}로 덮어쓴다.</li>
 *   <li>zone·referenceTime — {@link UserClock}(C-1: 엔진엔 Clock 없음)</li>
 * </ul>
 * <b>주의</b>: 엔진은 받은 것을 무조건 유효로 믿는다 — 주차예외·INACTIVE 필터를 여기서 안 걸면 없는 일정과의 오탐이 난다.
 */
@Component
public class PlanSnapshotAssembler {

    private static final int UNSET_PRIORITY = 0; // priority 미설정(null) 표현 — 현재 어떤 구현 규칙도 소비 안 함

    private final AvailabilityPatternRepository availabilityRepository;
    private final TaskRepository taskRepository;
    private final JdbcTemplate jdbc;
    private final UserClock clock;

    public PlanSnapshotAssembler(AvailabilityPatternRepository availabilityRepository,
                                 TaskRepository taskRepository, JdbcTemplate jdbc, UserClock clock) {
        this.availabilityRepository = availabilityRepository;
        this.taskRepository = taskRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 스냅샷 조립. {@code blocks}는 판정 대상(저장 초안 또는 가상). {@code taskEdits}는 dry-run 미저장 편집(없으면 빈 맵).
     */
    public PlanSnapshot assemble(UUID userId, LocalDate weekStartDate, List<BlockView> blocks,
                                 Map<UUID, VirtualTaskEdit> taskEdits) {
        ZoneId zone = clock.zoneOf(userId);

        List<AvailabilityWindow> availabilities = availabilityRepository.findByUserId(userId).stream()
                .map(PlanSnapshotAssembler::toAvailabilityWindow)
                .toList();

        List<FixedWindow> activeFixed = activeFixedWindows(userId, weekStartDate);

        Map<UUID, TaskFacts> taskFacts = taskFacts(blocks, taskEdits);

        return new PlanSnapshot(weekStartDate, zone, clock.now(), blocks, activeFixed, availabilities, taskFacts);
    }

    private static AvailabilityWindow toAvailabilityWindow(AvailabilityPattern p) {
        return new AvailabilityWindow(toDayOfWeek(p.getWeekday()), p.getStartTime(), p.getEndTime(), p.isActive());
    }

    /** 그 주 유효 고정일정 — ACTIVE + 주차예외 안티조인. baseline 테이블 직접 읽기(잠정 SQL). */
    private List<FixedWindow> activeFixedWindows(UUID userId, LocalDate weekStartDate) {
        return jdbc.query("""
                SELECT fs.fixed_schedule_id, fs.weekday, fs.start_time, fs.end_time, fs.start_date, fs.end_date
                FROM fixed_schedules fs
                WHERE fs.user_id = ?
                  AND fs.status = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1 FROM fixed_schedule_week_exceptions e
                      WHERE e.fixed_schedule_id = fs.fixed_schedule_id AND e.week_start_date = ?)
                """,
                (rs, n) -> new FixedWindow(
                        rs.getObject("fixed_schedule_id", UUID.class),
                        toDayOfWeek(Weekday.valueOf(rs.getString("weekday"))),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)),
                userId, weekStartDate);
    }

    /** 블록이 가리키는 태스크의 사실. dry-run 편집(taskEdits)이 있으면 덮어쓴다. WBS는 미구현이라 null. */
    private Map<UUID, TaskFacts> taskFacts(List<BlockView> blocks, Map<UUID, VirtualTaskEdit> taskEdits) {
        List<UUID> taskIds = blocks.stream()
                .map(BlockView::taskId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TaskFacts> facts = taskRepository.findAllById(taskIds).stream()
                .collect(Collectors.toMap(Task::getId, t -> toTaskFacts(t, taskEdits.get(t.getId()))));
        return facts;
    }

    private static TaskFacts toTaskFacts(Task t, VirtualTaskEdit edit) {
        LocalDate dueDate = t.getDueDate();
        int estimatedMinutes = t.getEstimatedMinutes() == null ? 0 : t.getEstimatedMinutes();
        int priority = t.getPriority() == null ? UNSET_PRIORITY : t.getPriority();
        if (edit != null) { // dry-run 미저장 편집 반영 (V5·V6 입력)
            if (edit.estimatedMinutes() != null) {
                estimatedMinutes = edit.estimatedMinutes();
            }
            dueDate = edit.dueDate();
        }
        return new TaskFacts(dueDate, null, null, estimatedMinutes, priority);
    }

    /** common.Weekday(MON..SUN) → java DayOfWeek(MONDAY..SUNDAY). 선언 순서가 월~일로 1:1. */
    private static DayOfWeek toDayOfWeek(Weekday weekday) {
        return DayOfWeek.of(weekday.ordinal() + 1);
    }
}
