package com.openplan.backend.push.infra;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.push.domain.PushLeadTime;
import com.openplan.backend.push.service.PushTargetReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 발송 대상 판정 재료 (JDBC) — TASK/SCHEDULE/FIXED_SCHEDULE/계획·일정·설정·구독 다섯 테이블을 가로질러
 * 읽으므로 JPA 연관을 새로 만들지 않고 SQL로 좁혀 뜬다({@code JdbcNotificationSourceReader}와 같은 이유).
 *
 * <p>설정 행이 없는 사용자는 기본값(전부 켜짐 · 10분 전)으로 읽는다 — 그래서 {@code push_settings}는
 * LEFT JOIN 이고 값마다 COALESCE 한다(2026-10-11 개정. 그 전에는 «행 없음 = 전부 꺼짐» 이라 INNER JOIN 이었다).
 *
 * <p>세 쿼리 모두 {@code EXISTS(... push_subscriptions ...)}로 "구독이 1개도 없는 사용자"를 선제적으로
 * 걸러낸다 — 구독 없는 사용자의 블록까지 디스패처로 넘기면 매 틱 쓸모없는 행을 만들게 된다.
 */
@Component
public class JdbcPushTargetReader implements PushTargetReader {

    /**
     * 발송 시각(시작 − 알림 시간)이 창에 드는가. 앞 줄은 {@code start_at} 인덱스를 타도록 거칠게 먼저 좁히는
     * 범위다 — 알림 시간이 아무리 길어도 시작은 {@code (창 시작, 창 끝 + 최대 알림 시간]} 안에 있다.
     * 바인딩 순서는 {@link #windowArgs} 와 1:1.
     */
    private static final String SEND_WINDOW = """
               AND pb.start_at > ? AND pb.start_at <= ?
               AND pb.start_at - make_interval(mins => COALESCE(ps.lead_minutes, ?)) > ?
               AND pb.start_at - make_interval(mins => COALESCE(ps.lead_minutes, ?)) <= ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcPushTargetReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PushTarget> taskTargets(Instant sendLowerExclusive, Instant sendUpperInclusive) {
        return jdbc.query("""
                SELECT pb.plan_block_id AS target_id, w.user_id AS user_id, pb.start_at AS start_at, t.title AS title,
                       COALESCE(ps.lead_minutes, ?) AS lead_minutes
                  FROM plan_blocks pb
                  JOIN weekly_plans w  ON w.weekly_plan_id = pb.weekly_plan_id
                  JOIN tasks t         ON t.task_id = pb.task_id
                  LEFT JOIN push_settings ps ON ps.user_id = w.user_id
                 WHERE pb.block_type = 'TASK'
                   AND pb.status = 'SCHEDULED'
                   AND w.status = 'CONFIRMED'
                   AND COALESCE(ps.task_enabled, true) = true
                """ + SEND_WINDOW + """
                   AND EXISTS (SELECT 1 FROM push_subscriptions sub WHERE sub.user_id = w.user_id)
                 ORDER BY pb.start_at
                """,
                JdbcPushTargetReader::target,
                windowArgs(sendLowerExclusive, sendUpperInclusive));
    }

    @Override
    public List<PushTarget> scheduleTargets(Instant sendLowerExclusive, Instant sendUpperInclusive) {
        return jdbc.query("""
                SELECT pb.plan_block_id AS target_id, w.user_id AS user_id, pb.start_at AS start_at, s.title AS title,
                       COALESCE(ps.lead_minutes, ?) AS lead_minutes
                  FROM plan_blocks pb
                  JOIN weekly_plans w  ON w.weekly_plan_id = pb.weekly_plan_id
                  JOIN schedules s     ON s.schedule_id = pb.schedule_id
                  LEFT JOIN push_settings ps ON ps.user_id = w.user_id
                 WHERE pb.block_type = 'SCHEDULE'
                   AND pb.status = 'SCHEDULED'
                   AND s.status = 'ACTIVE'
                   AND COALESCE(ps.schedule_enabled, true) = true
                """ + SEND_WINDOW + """
                   AND EXISTS (SELECT 1 FROM push_subscriptions sub WHERE sub.user_id = w.user_id)
                 ORDER BY pb.start_at
                """,
                JdbcPushTargetReader::target,
                windowArgs(sendLowerExclusive, sendUpperInclusive));
    }

    /** SELECT 의 COALESCE 1개 + {@link #SEND_WINDOW} 의 6개 — 순서가 곧 계약이다. */
    private static Object[] windowArgs(Instant sendLowerExclusive, Instant sendUpperInclusive) {
        int fallback = PushLeadTime.DEFAULT_MINUTES;
        Timestamp lower = Timestamp.from(sendLowerExclusive);
        Timestamp upper = Timestamp.from(sendUpperInclusive);
        Timestamp coarseUpper = Timestamp.from(sendUpperInclusive.plus(Duration.ofMinutes(PushLeadTime.MAX_MINUTES)));
        return new Object[]{fallback, lower, coarseUpper, fallback, lower, fallback, upper};
    }

    private static PushTarget target(ResultSet rs, int rowNum) throws SQLException {
        return new PushTarget(
                rs.getObject("user_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getString("title"),
                rs.getInt("lead_minutes"));
    }

    @Override
    public List<FixedScheduleCandidate> fixedScheduleCandidates() {
        return jdbc.query("""
                SELECT fs.fixed_schedule_id AS id, fs.user_id AS user_id, fs.title AS title,
                       fs.weekday AS weekday, fs.start_time AS start_time,
                       fs.start_date AS start_date, fs.end_date AS end_date,
                       COALESCE(ps.lead_minutes, ?) AS lead_minutes
                  FROM fixed_schedules fs
                  LEFT JOIN push_settings ps ON ps.user_id = fs.user_id
                 WHERE fs.status = 'ACTIVE'
                   AND COALESCE(ps.fixed_schedule_enabled, true) = true
                   AND EXISTS (SELECT 1 FROM push_subscriptions sub WHERE sub.user_id = fs.user_id)
                """,
                (rs, i) -> new FixedScheduleCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("title"),
                        Weekday.valueOf(rs.getString("weekday")),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("lead_minutes")),
                PushLeadTime.DEFAULT_MINUTES);
    }

    @Override
    public Map<UUID, Set<LocalDate>> weekExceptionsOf(List<UUID> fixedScheduleIds) {
        if (fixedScheduleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = fixedScheduleIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<UUID, Set<LocalDate>> result = new HashMap<>();
        jdbc.query("SELECT fixed_schedule_id, week_start_date FROM fixed_schedule_week_exceptions "
                        + "WHERE fixed_schedule_id IN (" + placeholders + ")",
                rs -> {
                    UUID id = rs.getObject("fixed_schedule_id", UUID.class);
                    LocalDate weekStart = rs.getObject("week_start_date", LocalDate.class);
                    result.computeIfAbsent(id, k -> new HashSet<>()).add(weekStart);
                },
                fixedScheduleIds.toArray());
        return result;
    }
}
