package com.openplan.backend.stats.service;

import com.openplan.backend.category.repository.TaskCategoryRepository;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.domain.ExecutionResult;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.stats.dto.DeviationReportResponse;
import com.openplan.backend.stats.dto.DeviationsQuery;
import com.openplan.backend.stats.dto.StatsSummaryQuery;
import com.openplan.backend.stats.dto.StatsSummaryResponse;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 수행 통계 서비스 단위 테스트(DB·Spring 불요, Docker 무관 — team-lead 지시로 신설). {@link AvailabilityServiceTest}와
 * 같은 패턴(Mockito 순수 목·AssertJ). {@link StatsQueryValidator}는 {@link com.openplan.backend.global.error.ErrorMessages}가
 * 자체 완결형(no-arg 생성자, classpath 리소스만 읽음)이라 목 없이 실물을 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    // 2026-07-15(수) 기준 MON 시작 주 = 07-13~07-19, zone=Asia/Seoul(WeekRangeTest와 동일 전제).
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock
    private ExecutionLogRepository executionLogRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private TaskCategoryRepository taskCategoryRepository;
    @Mock
    private UserClock clock;

    private StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(executionLogRepository, taskRepository, projectRepository,
                taskCategoryRepository, clock, new StatsQueryValidator(new com.openplan.backend.global.error.ErrorMessages()));
        // lenient — 유효성 오류(422) 테스트는 period 파싱에서 먼저 던지므로 이 스텁까지 도달하지 않는다.
        lenient().when(clock.todayOf(USER_ID)).thenReturn(TODAY);
        lenient().when(clock.zoneOf(USER_ID)).thenReturn(ZONE);
        lenient().when(clock.weekStartDayOf(USER_ID)).thenReturn(Weekday.MON);
    }

    @Test
    void summaries_이력0건이면_empty_true_반환() {
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                any(), any(), any())).thenReturn(List.of());

        StatsSummaryResponse response = service.summaries(USER_ID, query("WEEKLY", null));

        assertThat(response.empty()).isTrue();
        assertThat(response.totalEstimatedMinutes()).isZero();
        assertThat(response.completionRate()).isNull();
        assertThat(response.varianceRate()).isNull();
    }

    @Test
    void summaries_이력1건_집계값_계산() {
        Task task = new Task(PROJECT_ID, null, "태스크", null, 60, 1, null, Instant.now());
        ExecutionLog log = ExecutionLog.record(USER_ID, task.getId(), null,
                Instant.parse("2026-07-13T01:00:00Z"), Instant.parse("2026-07-13T02:30:00Z"),
                90, ExecutionResult.COMPLETED, null, Instant.now());
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                any(), any(), any())).thenReturn(List.of(log));
        when(taskRepository.findAllById(any())).thenReturn(List.of(task));

        StatsSummaryResponse response = service.summaries(USER_ID, query("WEEKLY", null));

        assertThat(response.empty()).isFalse();
        assertThat(response.totalEstimatedMinutes()).isEqualTo(60);
        assertThat(response.totalActualMinutes()).isEqualTo(90);
        assertThat(response.completionRate()).isEqualTo(100.0);
        assertThat(response.varianceRate()).isEqualTo(50.0); // (90-60)/60*100
    }

    @Test
    void summaries_period_미정의값이면_422_E_COM_009() {
        assertThatThrownBy(() -> service.summaries(USER_ID, query("YEARLY", null)))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(OpenPlanException.class))
                .extracting(OpenPlanException::errorCode)
                .isEqualTo(com.openplan.backend.global.error.ErrorCode.E_COM_009);
    }

    @Test
    void deviations_카테고리_미지정_태스크는_groupId_null_groupName_없음() {
        Task task = new Task(PROJECT_ID, null, "태스크", null, 40, 1, null, Instant.now());
        ExecutionLog log = ExecutionLog.record(USER_ID, task.getId(), null,
                Instant.parse("2026-07-13T01:00:00Z"), Instant.parse("2026-07-13T01:40:00Z"),
                40, ExecutionResult.COMPLETED, null, Instant.now());
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                any(), any(), any())).thenReturn(List.of(log));
        when(taskRepository.findAllById(any())).thenReturn(List.of(task));

        DeviationsQuery deviationsQuery = new DeviationsQuery();
        deviationsQuery.setPeriod("WEEKLY");
        deviationsQuery.setGroupBy("CATEGORY");
        DeviationReportResponse response = service.deviations(USER_ID, deviationsQuery);

        assertThat(response.empty()).isFalse();
        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).groupId()).isNull();
        assertThat(response.rows().get(0).groupName()).isEqualTo("없음");
        assertThat(response.rows().get(0).deviationMinutes()).isZero();
    }

    @Test
    void deviations_예상시간이_0이면_deviationRate는_null() {
        // estimatedMinutes=null(무입력) → nz()로 0 취급, 분모 0이라 rate는 null(0으로 나누지 않는다).
        Task task = new Task(PROJECT_ID, null, "태스크", null, null, 1, null, Instant.now());
        ExecutionLog log = ExecutionLog.record(USER_ID, task.getId(), null,
                Instant.parse("2026-07-13T01:00:00Z"), Instant.parse("2026-07-13T01:30:00Z"),
                30, ExecutionResult.COMPLETED, null, Instant.now());
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                any(), any(), any())).thenReturn(List.of(log));
        when(taskRepository.findAllById(any())).thenReturn(List.of(task));

        DeviationsQuery deviationsQuery = new DeviationsQuery();
        deviationsQuery.setPeriod("WEEKLY");
        deviationsQuery.setGroupBy("PROJECT");
        DeviationReportResponse response = service.deviations(USER_ID, deviationsQuery);

        assertThat(response.rows().get(0).estimatedMinutes()).isZero();
        assertThat(response.rows().get(0).actualMinutes()).isEqualTo(30);
        assertThat(response.rows().get(0).deviationMinutes()).isEqualTo(30);
        assertThat(response.rows().get(0).deviationRate()).isNull(); // 0 분모 회피
    }

    @Test
    void summaries_MONTHLY_period는_해당_월_1일부터_말일까지() {
        // 2026-02(평년, 28일) — 월 경계 rangeStart/rangeEnd 계산 검증.
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                any(), any(), any())).thenReturn(List.of());

        StatsSummaryResponse response = service.summaries(USER_ID, query("MONTHLY", LocalDate.of(2026, 2, 15)));

        assertThat(response.rangeStart()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(response.rangeEnd()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void summaries_MONTHLY_period는_12월이어도_다음해로_넘어가지_않는다() {
        // 연도 경계 sanity — 달력 월 계산이라 12월은 그 해 12-31까지만.
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                any(), any(), any())).thenReturn(List.of());

        StatsSummaryResponse response = service.summaries(USER_ID, query("MONTHLY", LocalDate.of(2026, 12, 10)));

        assertThat(response.rangeStart()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(response.rangeEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    private StatsSummaryQuery query(String period, LocalDate baseDate) {
        StatsSummaryQuery q = new StatsSummaryQuery();
        q.setPeriod(period);
        q.setBaseDate(baseDate);
        return q;
    }
}
