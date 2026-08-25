package com.openplan.backend.preference.service;

import com.openplan.backend.availability.dto.AvailabilityPatternDto;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.preference.dto.PreferenceSuggestionResponse;
import com.openplan.backend.rule.model.ReplanStrategy;
import com.openplan.backend.weeklyplan.domain.ReplanOption;
import com.openplan.backend.weeklyplan.repository.ReplanOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 규칙 기반 기본값 제안 단위 테스트(SS-14 · DB 불요).
 *
 * <p>고정하는 것은 <b>규칙</b>이다 — 중앙값·5분 격자·표본 하한·요일 창(窓)·최빈 전략. 값이 어떻게
 * 나오는지를 테스트가 말할 수 있어야 사용자에게 보이는 {@code reason} 이 검증 가능해진다(P4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreferenceSuggestionServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 목요일 정오(KST) 기준으로 관측창을 잡는다. */
    private static final Instant NOW = ZonedDateTime.of(2026, 8, 20, 12, 0, 0, 0, SEOUL).toInstant();

    @Mock private ExecutionLogRepository executionLogRepository;
    @Mock private ReplanOptionRepository replanOptionRepository;
    @Mock private UserClock clock;

    private PreferenceSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new PreferenceSuggestionService(executionLogRepository, replanOptionRepository, clock);
        when(clock.now()).thenReturn(NOW);
        when(clock.zoneOf(USER_ID)).thenReturn(SEOUL);
        when(replanOptionRepository.findSelectedByUserIdAndSelectedAtRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("표본이 하한(5건) 미만이면 오류가 아니라 null 이다")
    void returnsNullWhenHistoryTooThin() {
        givenLogs(log(day(1, 9, 0), day(1, 9, 30), 30),
                  log(day(2, 9, 0), day(2, 9, 30), 30),
                  log(day(3, 9, 0), day(3, 9, 30), 30),
                  log(day(4, 9, 0), day(4, 9, 30), 30));

        assertThat(service.suggest(USER_ID)).isNull();
    }

    @Test
    @DisplayName("홀수 표본의 중앙값을 5분 격자로 낸다 — 평균이 아니다")
    void suggestsMedianNotMean() {
        // 10·20·30·40·600 → 중앙값 30 (평균이면 140)
        givenLogs(log(day(1, 9, 0), day(1, 9, 10), 10),
                  log(day(2, 9, 0), day(2, 9, 20), 20),
                  log(day(3, 9, 0), day(3, 9, 30), 30),
                  log(day(4, 9, 0), day(4, 9, 40), 40),
                  log(day(5, 9, 0), day(5, 19, 0), 600));

        PreferenceSuggestionResponse res = service.suggest(USER_ID);

        assertThat(res).isNotNull();
        assertThat(res.suggestedEstimatedMinutes()).isEqualTo(30);
        assertThat(res.reason()).contains("중앙값 30분");
        assertThat(res.reason()).doesNotContainIgnoringCase("AI");
    }

    @Test
    @DisplayName("짝수 표본은 가운데 두 값의 평균을 5분 격자로 반올림한다")
    void roundsToFiveMinuteGrid() {
        // 10·20·30·40·50·60 → 가운데 30·40 평균 35
        givenLogs(log(day(1, 9, 0), day(1, 9, 10), 10),
                  log(day(2, 9, 0), day(2, 9, 20), 20),
                  log(day(3, 9, 0), day(3, 9, 30), 30),
                  log(day(4, 9, 0), day(4, 9, 40), 40),
                  log(day(5, 9, 0), day(5, 9, 50), 50),
                  log(day(6, 9, 0), day(6, 10, 0), 60));

        assertThat(service.suggest(USER_ID).suggestedEstimatedMinutes()).isEqualTo(35);
    }

    @Test
    @DisplayName("요일별 창은 실제 수행 시간을 감싸도록 바깥으로 반올림한다")
    void availabilityWindowEnclosesActualWork() {
        // 같은 요일(월)에 2건 — 09:03~09:47, 14:12~15:01 → 09:00 ~ 15:05
        givenLogs(log(day(1, 9, 3), day(1, 9, 47), 45),
                  log(day(1, 14, 12), day(1, 15, 1), 50),
                  log(day(2, 9, 0), day(2, 9, 30), 30),
                  log(day(3, 9, 0), day(3, 9, 30), 30),
                  log(day(4, 9, 0), day(4, 9, 30), 30),
                  log(day(5, 9, 0), day(5, 9, 30), 30));

        List<AvailabilityPatternDto> patterns = service.suggest(USER_ID).suggestedAvailabilities();

        AvailabilityPatternDto monday = patterns.stream()
                .filter(p -> p.weekday() == Weekday.MON).findFirst().orElseThrow();
        assertThat(monday.startTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.endTime()).isEqualTo(LocalTime.of(15, 5));
        assertThat(monday.isActive()).isTrue();
    }

    @Test
    @DisplayName("한 건뿐인 요일은 제안하지 않는다 — 우연을 근거로 권하지 않는다")
    void skipsWeekdayWithSingleSample() {
        givenLogs(log(day(1, 9, 0), day(1, 9, 30), 30),   // 월 1건
                  log(day(2, 9, 0), day(2, 9, 30), 30),
                  log(day(2, 14, 0), day(2, 14, 30), 30), // 화 2건
                  log(day(3, 9, 0), day(3, 9, 30), 30),
                  log(day(4, 9, 0), day(4, 9, 30), 30));

        List<Weekday> days = service.suggest(USER_ID).suggestedAvailabilities()
                .stream().map(AvailabilityPatternDto::weekday).toList();

        assertThat(days).contains(Weekday.TUE).doesNotContain(Weekday.MON);
    }

    @Test
    @DisplayName("실제로 고른 전략의 최빈값을 낸다 — 동률이면 더 최근 것")
    void suggestsMostSelectedStrategy() {
        givenLogs(log(day(1, 9, 0), day(1, 9, 30), 30),
                  log(day(2, 9, 0), day(2, 9, 30), 30),
                  log(day(3, 9, 0), day(3, 9, 30), 30),
                  log(day(4, 9, 0), day(4, 9, 30), 30),
                  log(day(5, 9, 0), day(5, 9, 30), 30));
        ReplanStrategy first = ReplanStrategy.values()[0];
        ReplanStrategy second = ReplanStrategy.values()[1];
        when(replanOptionRepository.findSelectedByUserIdAndSelectedAtRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(
                        option(second, NOW.minusSeconds(9000)),
                        option(first, NOW.minusSeconds(8000)),
                        option(first, NOW.minusSeconds(7000))));

        assertThat(service.suggest(USER_ID).suggestedReplanStrategy()).isEqualTo(first.name());
    }

    @Test
    @DisplayName("종료 시각이 없는 기록은 창 계산에서 건너뛴다 — 중앙값에는 남는다")
    void ignoresUnfinishedLogsForWindow() {
        List<ExecutionLog> logs = new ArrayList<>(List.of(
                log(day(1, 9, 0), day(1, 9, 30), 30),
                log(day(1, 10, 0), null, 30),
                log(day(2, 9, 0), day(2, 9, 30), 30),
                log(day(3, 9, 0), day(3, 9, 30), 30),
                log(day(4, 9, 0), day(4, 9, 30), 30),
                log(day(5, 9, 0), day(5, 9, 30), 30)));
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                eq(USER_ID), any(), any())).thenReturn(logs);

        PreferenceSuggestionResponse res = service.suggest(USER_ID);

        assertThat(res.suggestedEstimatedMinutes()).isEqualTo(30);
        // 월요일은 완결 기록이 1건뿐 → 요일 하한(2건) 미달로 빠진다
        assertThat(res.suggestedAvailabilities().stream().map(AvailabilityPatternDto::weekday))
                .doesNotContain(Weekday.MON);
    }

    // ---------------------------------------------------------------- 픽스처

    private void givenLogs(ExecutionLog... logs) {
        when(executionLogRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                eq(USER_ID), any(), any())).thenReturn(List.of(logs));
    }

    /** 관측창 안의 특정 요일(1=월 … 7=일) 시각. 2026-08-17 이 월요일이라 그 주를 기준으로 잡는다. */
    private static Instant day(int isoDayOfWeek, int hour, int minute) {
        return ZonedDateTime.of(2026, 8, 17, 0, 0, 0, 0, SEOUL)
                .plusDays(isoDayOfWeek - 1L)
                .withHour(hour).withMinute(minute)
                .toInstant();
    }

    private static ExecutionLog log(Instant startedAt, Instant endedAt, Integer actualMinutes) {
        ExecutionLog e = instantiate(ExecutionLog.class);
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(e, "userId", USER_ID);
        ReflectionTestUtils.setField(e, "startedAt", startedAt);
        ReflectionTestUtils.setField(e, "endedAt", endedAt);
        ReflectionTestUtils.setField(e, "actualMinutes", actualMinutes);
        return e;
    }

    private static ReplanOption option(ReplanStrategy strategy, Instant selectedAt) {
        ReplanOption o = instantiate(ReplanOption.class);
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(o, "strategyType", strategy);
        ReflectionTestUtils.setField(o, "selected", true);
        ReflectionTestUtils.setField(o, "selectedAt", selectedAt);
        return o;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 픽스처 생성 실패: " + type.getSimpleName(), e);
        }
    }
}
