package com.openplan.backend.push.infra;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.push.service.PushTargetReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
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
 * <p>세 쿼리 모두 {@code EXISTS(... push_subscriptions ...)}로 "구독이 1개도 없는 사용자"를 선제적으로
 * 걸러낸다 — 구독 없는 사용자의 블록까지 디스패처로 넘기면 매 틱 쓸모없는 행을 만들게 된다.
 */
@Component
public class JdbcPushTargetReader implements PushTargetReader {

    private final JdbcTemplate jdbc;

    public JdbcPushTargetReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PushTarget> taskTargets(Instant lowerExclusive, Instant upperInclusive) {
        return jdbc.query("""
                SELECT pb.plan_block_id AS target_id, w.user_id AS user_id, pb.start_at AS start_at, t.title AS title
                  FROM plan_blocks pb
                  JOIN weekly_plans w  ON w.weekly_plan_id = pb.weekly_plan_id
                  JOIN tasks t         ON t.task_id = pb.task_id
                  JOIN push_settings ps ON ps.user_id = w.user_id
                 WHERE pb.block_type = 'TASK'
                   AND pb.status = 'SCHEDULED'
                   AND w.status = 'CONFIRMED'
                   AND ps.task_enabled = true
                   AND pb.start_at > ? AND pb.start_at <= ?
                   AND EXISTS (SELECT 1 FROM push_subscriptions sub WHERE sub.user_id = w.user_id)
                 ORDER BY pb.start_at
                """,
                (rs, i) -> new PushTarget(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("target_id", UUID.class),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getString("title")),
                Timestamp.from(lowerExclusive), Timestamp.from(upperInclusive));
    }

    @Override
    public List<PushTarget> scheduleTargets(Instant lowerExclusive, Instant upperInclusive) {
        return jdbc.query("""
                SELECT pb.plan_block_id AS target_id, w.user_id AS user_id, pb.start_at AS start_at, s.title AS title
                  FROM plan_blocks pb
                  JOIN weekly_plans w  ON w.weekly_plan_id = pb.weekly_plan_id
                  JOIN schedules s     ON s.schedule_id = pb.schedule_id
                  JOIN push_settings ps ON ps.user_id = w.user_id
                 WHERE pb.block_type = 'SCHEDULE'
                   AND pb.status = 'SCHEDULED'
                   AND s.status = 'ACTIVE'
                   AND ps.schedule_enabled = true
                   AND pb.start_at > ? AND pb.start_at <= ?
                   AND EXISTS (SELECT 1 FROM push_subscriptions sub WHERE sub.user_id = w.user_id)
                 ORDER BY pb.start_at
                """,
                (rs, i) -> new PushTarget(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("target_id", UUID.class),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getString("title")),
                Timestamp.from(lowerExclusive), Timestamp.from(upperInclusive));
    }

    @Override
    public List<FixedScheduleCandidate> fixedScheduleCandidates() {
        return jdbc.query("""
                SELECT fs.fixed_schedule_id AS id, fs.user_id AS user_id, fs.title AS title,
                       fs.weekday AS weekday, fs.start_time AS start_time,
                       fs.start_date AS start_date, fs.end_date AS end_date
                  FROM fixed_schedules fs
                  JOIN push_settings ps ON ps.user_id = fs.user_id
                 WHERE fs.status = 'ACTIVE'
                   AND ps.fixed_schedule_enabled = true
                   AND EXISTS (SELECT 1 FROM push_subscriptions sub WHERE sub.user_id = fs.user_id)
                """,
                (rs, i) -> new FixedScheduleCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("title"),
                        Weekday.valueOf(rs.getString("weekday")),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)));
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
