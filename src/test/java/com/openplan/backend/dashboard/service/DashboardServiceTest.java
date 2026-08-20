package com.openplan.backend.dashboard.service;

import com.openplan.backend.availability.repository.AvailabilityPatternRepository;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.dashboard.dto.DashboardQuery;
import com.openplan.backend.dashboard.dto.DashboardResponse;
import com.openplan.backend.dashboard.service.port.DashboardPlanReader;
import com.openplan.backend.dashboard.service.port.TodayBlockRow;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.ProjectAutoCloseEvaluator;
import com.openplan.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 대시보드 조립 서비스 단위 테스트(DB·Spring·Docker 불요 — team-lead 지시로 신설). 전 협력자를 목으로
 * 대체해 {@link DashboardService#getDashboard}를 직접 호출한다({@link AvailabilityServiceTest}와 동일 패턴).
 *
 * <p>고정하는 것: RB-DASH-01 전순서(FIXED_CONFLICT가 UNASSIGNED보다 우선) · 후보 0건→null(긍정 상태) ·
 * riskIssues count=0 유형 제외 · DASH-07 전 요일 가용 0이면 빈 배열.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15); // 수(WeekRangeTest와 동일 전제)
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 13);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant REFERENCE_TIME = Instant.parse("2026-07-15T00:00:00Z"); // KST 09:00

    @Mock
    private DashboardPlanReader planReader;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ExecutionLogRepository executionLogRepository;
    @Mock
    private AvailabilityPatternRepository availabilityPatternRepository;
    @Mock
    private ProjectAutoCloseEvaluator projectAutoCloseEvaluator;
    @Mock
    private UserClock clock;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(planReader, taskRepository, projectRepository, executionLogRepository,
                availabilityPatternRepository, projectAutoCloseEvaluator, clock);

        // 전 테스트가 공유하는 무조건 호출분(§ 클래스 javadoc) — 안전한 빈 기본값.
        when(clock.now()).thenReturn(REFERENCE_TIME);
        when(clock.todayOf(USER_ID)).thenReturn(TODAY);
        when(clock.weekStartDayOf(USER_ID)).thenReturn(Weekday.MON);
        when(clock.zoneOf(USER_ID)).thenReturn(ZONE);
        when(availabilityPatternRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(planReader.todayBlocks(USER_ID, TODAY, ZONE)).thenReturn(List.of());
        when(planReader.openIssueCounts(USER_ID, WEEK_START)).thenReturn(Map.of());
        when(taskRepository.countUnassigned(USER_ID)).thenReturn(0L);
        when(taskRepository.countDeadlineSoon(any(), any(), any())).thenReturn(0L);
        when(planReader.totalPlannedMinutes(USER_ID, WEEK_START)).thenReturn(0);
        when(projectRepository.findByUserIdAndStatusIn(any(), anyList(), any())).thenReturn(Page.empty());
        when(planReader.plannedMinutesByWeekday(any(), any(), any(), any())).thenReturn(Map.of());
    }

    @Test
    void 후보_0건이면_priorityAction_null_긍정상태() {
        DashboardResponse response = service.getDashboard(USER_ID, new DashboardQuery());

        assertThat(response.priorityAction()).isNull();
        assertThat(response.riskIssues()).isEmpty();
        assertThat(response.busyWeekdays()).isEmpty(); // 가용 0 → 전부 제외(D-5.2)
    }

    @Test
    void FIXED_CONFLICT가_UNASSIGNED보다_우선한다_Q3_전순서() {
        // 둘 다 후보인 상황 — Q3 §3.2 1위(FIXED_CONFLICT)가 3위(UNASSIGNED)를 이겨야 한다.
        when(planReader.openIssueCounts(USER_ID, WEEK_START)).thenReturn(Map.of("V2_FIXED_CONFLICT", 1L));
        when(taskRepository.countUnassigned(USER_ID)).thenReturn(5L);

        DashboardResponse response = service.getDashboard(USER_ID, new DashboardQuery());

        assertThat(response.priorityAction()).isNotNull();
        assertThat(response.priorityAction().actionType()).isEqualTo("RESOLVE_FIXED_CONFLICT");
    }

    @Test
    void UNASSIGNED만_있으면_PLACE_UNASSIGNED_riskIssues에도_반영() {
        when(taskRepository.countUnassigned(USER_ID)).thenReturn(2L);

        DashboardResponse response = service.getDashboard(USER_ID, new DashboardQuery());

        assertThat(response.priorityAction().actionType()).isEqualTo("PLACE_UNASSIGNED");
        assertThat(response.riskIssues()).hasSize(1);
        assertThat(response.riskIssues().get(0).riskType()).isEqualTo("UNASSIGNED_TASKS");
        assertThat(response.riskIssues().get(0).count()).isEqualTo(2);
    }

    @Test
    void 오늘_종료시각이_지난_미완료_TASK블록이면_REPLACE_TODAY_INCOMPLETE() {
        UUID taskId = UUID.randomUUID();
        TodayBlockRow incompleteBlock = new TodayBlockRow(
                UUID.randomUUID(), taskId, "제목",
                Instant.parse("2026-07-14T23:00:00Z"), Instant.parse("2026-07-14T23:30:00Z"),
                30, false, true); // endAt(23:30Z) <= referenceTime(07-15T00:00Z), 미완료, TASK
        when(planReader.todayBlocks(USER_ID, TODAY, ZONE)).thenReturn(List.of(incompleteBlock));

        DashboardResponse response = service.getDashboard(USER_ID, new DashboardQuery());

        assertThat(response.priorityAction().actionType()).isEqualTo("REPLACE_TODAY_INCOMPLETE");
        assertThat(response.todayBoard().items()).hasSize(1);
        assertThat(response.todayBoard().items().get(0).selectionRank()).isNull(); // SS-02 타이브레이크 미정
    }

    @Test
    void 완료된_오늘블록만_있으면_트리거되지_않는다() {
        TodayBlockRow completedBlock = new TodayBlockRow(
                UUID.randomUUID(), UUID.randomUUID(), "제목",
                Instant.parse("2026-07-14T23:00:00Z"), Instant.parse("2026-07-14T23:30:00Z"),
                30, true, true); // completed=true
        when(planReader.todayBlocks(USER_ID, TODAY, ZONE)).thenReturn(List.of(completedBlock));

        DashboardResponse response = service.getDashboard(USER_ID, new DashboardQuery());

        assertThat(response.priorityAction()).isNull();
    }

    @Test
    void riskIssues는_count가_0인_유형을_제외한다() {
        // V2(FIXED_CONFLICT)만 1건 — V5(OUT_OF_WBS)는 0이라 목록에 나오면 안 된다.
        when(planReader.openIssueCounts(USER_ID, WEEK_START)).thenReturn(Map.of("V2_FIXED_CONFLICT", 1L));

        DashboardResponse response = service.getDashboard(USER_ID, new DashboardQuery());

        assertThat(response.riskIssues()).hasSize(1);
        assertThat(response.riskIssues().get(0).riskType()).isEqualTo("FIXED_CONFLICT");
    }
}
