package com.openplan.backend.notification.infra;

import com.openplan.backend.notification.service.NotificationSourceReader;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 판정 재료 조회 (JDBC). 네 도메인을 가로질러 읽으므로 JPA 연관을 만들지 않고 SQL 로 좁게 뜬다.
 *
 * <p><b>날짜 경계는 SQL 에서 사용자 zone 으로 환산한다</b> — {@code AT TIME ZONE}. 자바에서 Instant 로
 * 바꿔 넘기면 경계가 두 곳(자바·DB)에 생겨 어긋날 자리가 늘어난다.
 */
@Component
public class JdbcNotificationSourceReader implements NotificationSourceReader {

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    public JdbcNotificationSourceReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code UserClock} 이 같은 조회를 private 으로 갖고 있으나 아직 노출하지 않는다.
     * BE-2 레인의 PR 두 건(#26·#28)이 {@code UserClock.zoneOf} 를 신설 중이라, 여기서 같은 메서드를
     * 또 더하면 3중 충돌이 된다. <b>그 둘이 main 에 들어오면 이 구현을 지우고 clock 으로 옮긴다.</b>
     */
    @Override
    public ZoneId zoneOf(UUID userId) {
        try {
            String tz = jdbc.queryForObject(
                    "SELECT timezone FROM user_profiles WHERE user_id = ?", String.class, userId);
            return tz == null || tz.isBlank() ? FALLBACK_ZONE : ZoneId.of(tz);
        } catch (EmptyResultDataAccessException | java.time.DateTimeException e) {
            return FALLBACK_ZONE; // 행 부재 · 알 수 없는 zone 문자열
        }
    }

    /** 소유는 {@code tasks → projects.user_id} 조인으로 판정한다(tasks 에 user_id 없음 — D-16). */
    @Override
    public List<DeadlineTask> deadlineSoonTasks(UUID userId, LocalDate today) {
        return jdbc.query("""
                SELECT t.task_id, t.title, t.due_date
                  FROM tasks t
                  JOIN projects p ON p.project_id = t.project_id
                 WHERE p.user_id = ?
                   AND t.status <> 'COMPLETED'
                   AND t.due_date IS NOT NULL
                   AND t.due_date >= ?
                   AND t.due_date <= ?
                 ORDER BY t.due_date, t.task_id
                """,
                (rs, i) -> new DeadlineTask(
                        rs.getObject("task_id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("due_date", LocalDate.class)),
                userId, today, today.plusDays(3));
    }

    @Override
    public Optional<TodayTasks> todayTasks(UUID userId, LocalDate today) {
        List<TodayTasks> rows = jdbc.query("""
                SELECT w.weekly_plan_id, COUNT(*) AS cnt
                  FROM plan_blocks b
                  JOIN weekly_plans w ON w.weekly_plan_id = b.weekly_plan_id
                  JOIN user_profiles up ON up.user_id = w.user_id
                 WHERE w.user_id = ?
                   AND b.block_type = 'TASK'
                   AND b.status <> 'COMPLETED'
                   AND (b.start_at AT TIME ZONE COALESCE(up.timezone, 'Asia/Seoul'))::date = ?
                 GROUP BY w.weekly_plan_id
                 ORDER BY w.weekly_plan_id
                """,
                (rs, i) -> new TodayTasks(rs.getObject("weekly_plan_id", UUID.class), rs.getInt("cnt")),
                userId, today);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UUID> draftWeeklyPlanId(UUID userId, LocalDate weekStart) {
        return jdbc.query("""
                SELECT weekly_plan_id FROM weekly_plans
                 WHERE user_id = ? AND week_start_date = ? AND status = 'DRAFT'
                """,
                rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.<UUID>empty(),
                userId, weekStart);
    }

    @Override
    public int lastWeekExecutionLogCount(UUID userId, LocalDate weekStart) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM execution_logs e
                  JOIN user_profiles up ON up.user_id = e.user_id
                 WHERE e.user_id = ?
                   AND (e.started_at AT TIME ZONE COALESCE(up.timezone, 'Asia/Seoul'))::date >= ?
                   AND (e.started_at AT TIME ZONE COALESCE(up.timezone, 'Asia/Seoul'))::date < ?
                """, Integer.class, userId, weekStart.minusDays(7), weekStart);
        return n == null ? 0 : n;
    }

    @Override
    public List<AnsweredTicket> answeredTickets(UUID userId) {
        return jdbc.query("""
                SELECT support_ticket_id, title FROM support_tickets
                 WHERE user_id = ? AND answered_at IS NOT NULL
                 ORDER BY answered_at, support_ticket_id
                """,
                (rs, i) -> new AnsweredTicket(
                        rs.getObject("support_ticket_id", UUID.class), rs.getString("title")),
                userId);
    }
}
