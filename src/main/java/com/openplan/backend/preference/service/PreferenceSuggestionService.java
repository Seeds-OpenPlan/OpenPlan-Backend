package com.openplan.backend.preference.service;

import com.openplan.backend.availability.dto.AvailabilityPatternDto;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.preference.dto.PreferenceSuggestionResponse;
import com.openplan.backend.weeklyplan.domain.ReplanOption;
import com.openplan.backend.weeklyplan.repository.ReplanOptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 규칙 기반 기본값 제안 (SS-14 · {@code GET /users/me/preference-suggestions}).
 *
 * <p><b>규칙이지 학습이 아니다.</b> 중앙값과 빈도만 쓴다 — 사용자가 화면의 숫자를 자기 이력과
 * 대조해 검증할 수 있어야 하고, 그래서 {@code reason} 도 계산식을 그대로 말한다(P4 — AI 표현 금지).
 *
 * <p><b>이력이 모자라면 오류가 아니라 {@code null} 이다</b>(계약 200 · data=null). 제안은 편의이지
 * 기능이 아니므로, 없을 때 사용자에게 오류를 보이면 "뭔가 잘못됐다"는 잘못된 신호를 준다.
 */
@Service
public class PreferenceSuggestionService {

    /**
     * 관측 창 4주. {@code reason} 문구("최근 4주 …")가 계약 예시에 박혀 있어 설정으로 빼지 않았다 —
     * 값과 설명이 갈라지면 사용자가 대조할 수 없다.
     */
    private static final Duration WINDOW = Duration.ofDays(28);

    /**
     * 🔴 제안을 내는 최소 표본. 1~2건의 중앙값은 그냥 그 값이라, 이력을 근거로 제시하면
     * 근거가 아니라 우연을 권하게 된다. 요일별 제안은 더 좁으므로 별도 하한을 둔다.
     */
    private static final int MIN_LOGS_FOR_ESTIMATE = 5;
    private static final int MIN_LOGS_PER_WEEKDAY = 2;

    /** 계약이 {@code multipleOf: 5} 를 요구한다 — 가용시간 패턴의 5분 격자와도 같은 단위다. */
    private static final int MINUTE_GRID = 5;

    private final ExecutionLogRepository executionLogRepository;
    private final ReplanOptionRepository replanOptionRepository;
    private final UserClock clock;

    public PreferenceSuggestionService(ExecutionLogRepository executionLogRepository,
                                       ReplanOptionRepository replanOptionRepository,
                                       UserClock clock) {
        this.executionLogRepository = executionLogRepository;
        this.replanOptionRepository = replanOptionRepository;
        this.clock = clock;
    }

    /**
     * @return 제안. 표본이 하한에 못 미치면 {@code null} — 컨트롤러가 {@code data: null} 로 싣는다.
     */
    @Transactional(readOnly = true)
    public PreferenceSuggestionResponse suggest(UUID userId) {
        Instant now = clock.now();
        Instant from = now.minus(WINDOW);
        ZoneId zone = clock.zoneOf(userId);

        List<ExecutionLog> logs = executionLogRepository
                .findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(userId, from, now);

        List<Integer> actuals = logs.stream()
                .map(ExecutionLog::getActualMinutes)
                .filter(m -> m != null && m > 0)
                .sorted()
                .toList();

        if (actuals.size() < MIN_LOGS_FOR_ESTIMATE) {
            return null; // 이력 부족 — 오류가 아니다
        }

        int median = roundToGrid(median(actuals));
        List<AvailabilityPatternDto> availabilities = suggestAvailabilities(logs, zone);
        String strategy = mostSelectedStrategy(userId, from, now);

        String reason = "최근 4주 실행 기록 %d건의 실제 시간 중앙값 %d분 기준".formatted(actuals.size(), median)
                + (availabilities.isEmpty() ? "" : " · 요일별 실제 수행 시간대 %d개".formatted(availabilities.size()))
                + (strategy == null ? "" : " · 가장 많이 고른 재계획 전략");

        return new PreferenceSuggestionResponse(median, strategy, availabilities, reason);
    }

    /** 짝수 개면 가운데 두 값의 평균 — 정렬된 입력을 전제로 한다. */
    private static int median(List<Integer> sorted) {
        int n = sorted.size();
        int mid = n / 2;
        return (n % 2 == 1) ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /** 5분 격자로 반올림하되 0으로 내려가지 않는다 — 0분 제안은 값이 아니라 결함으로 읽힌다. */
    private static int roundToGrid(int minutes) {
        int rounded = Math.round((float) minutes / MINUTE_GRID) * MINUTE_GRID;
        return Math.max(MINUTE_GRID, rounded);
    }

    /**
     * 실제로 수행한 시간대를 요일별로 모아 창(窓) 하나로 제안한다 — 가장 이른 시작 ~ 가장 늦은 종료.
     *
     * <p><b>바깥으로 반올림한다.</b> 안쪽으로 깎으면 실제로 일한 시간이 가용 범위 밖으로 밀려나고,
     * 그 상태로 적용하면 규칙엔진이 자기 이력을 위반으로 잡는다.
     *
     * <p>종료 시각이 없는 기록(진행 중)은 건너뛴다. 자정을 넘긴 기록은 종료를 23:55 로 자른다 —
     * 요일 경계를 넘는 창은 계약의 (요일, 시작, 종료) 한 행으로 표현할 수 없다.
     */
    private List<AvailabilityPatternDto> suggestAvailabilities(List<ExecutionLog> logs, ZoneId zone) {
        Map<Weekday, int[]> spanByDay = new EnumMap<>(Weekday.class); // [최소시작분, 최대종료분, 건수]

        for (ExecutionLog logEntry : logs) {
            Instant startedAt = logEntry.getStartedAt();
            Instant endedAt = logEntry.getEndedAt();
            if (startedAt == null || endedAt == null || !endedAt.isAfter(startedAt)) {
                continue;
            }
            LocalDateTime start = LocalDateTime.ofInstant(startedAt, zone);
            LocalDateTime end = LocalDateTime.ofInstant(endedAt, zone);

            Weekday day = Weekday.values()[start.getDayOfWeek().getValue() - 1];
            int startMin = start.getHour() * 60 + start.getMinute();
            int endMin = end.toLocalDate().equals(start.toLocalDate())
                    ? end.getHour() * 60 + end.getMinute()
                    : 23 * 60 + 55; // 자정을 넘긴 기록은 그날 끝으로 자른다

            int[] span = spanByDay.computeIfAbsent(day, d -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 0});
            span[0] = Math.min(span[0], floorToGrid(startMin));
            span[1] = Math.max(span[1], ceilToGrid(endMin));
            span[2]++;
        }

        List<AvailabilityPatternDto> out = new ArrayList<>();
        for (Map.Entry<Weekday, int[]> e : spanByDay.entrySet()) {
            int[] span = e.getValue();
            if (span[2] < MIN_LOGS_PER_WEEKDAY || span[1] <= span[0]) {
                continue;
            }
            out.add(new AvailabilityPatternDto(
                    e.getKey(),
                    LocalTime.of(span[0] / 60, span[0] % 60),
                    LocalTime.of(Math.min(span[1], 23 * 60 + 55) / 60, Math.min(span[1], 23 * 60 + 55) % 60),
                    true));
        }
        out.sort(Comparator.comparing(AvailabilityPatternDto::weekday));
        return out;
    }

    private static int floorToGrid(int minutes) {
        return (minutes / MINUTE_GRID) * MINUTE_GRID;
    }

    private static int ceilToGrid(int minutes) {
        return ((minutes + MINUTE_GRID - 1) / MINUTE_GRID) * MINUTE_GRID;
    }

    /**
     * 창 안에서 <b>실제로 고른</b> 재계획 전략 중 최빈값. 동률이면 더 최근에 고른 쪽을 택한다 —
     * 동률을 null 로 버리면 두 전략을 번갈아 쓴 사용자에게 아무 제안도 못 준다.
     *
     * <p>현재 설정값({@code user_preferences.default_replan_strategy})은 보지 않는다. 그건 제안의
     * 근거가 아니라 제안이 바꾸려는 대상이다.
     */
    private String mostSelectedStrategy(UUID userId, Instant from, Instant to) {
        List<ReplanOption> selected = replanOptionRepository.findSelectedByUserIdAndSelectedAtRange(userId, from, to);
        if (selected.isEmpty()) {
            return null;
        }
        Map<String, int[]> freq = new java.util.HashMap<>(); // [횟수, 최근선택시각(epoch초)]
        for (ReplanOption o : selected) {
            String key = o.getStrategyType().name();
            int recent = (int) o.getSelectedAt().getEpochSecond();
            int[] cur = freq.computeIfAbsent(key, k -> new int[]{0, Integer.MIN_VALUE});
            cur[0]++;
            cur[1] = Math.max(cur[1], recent);
        }
        return freq.entrySet().stream()
                .max(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> e.getValue()[0])
                        .thenComparingInt(e -> e.getValue()[1]))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
