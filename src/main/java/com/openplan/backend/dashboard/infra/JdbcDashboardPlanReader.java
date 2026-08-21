package com.openplan.backend.dashboard.infra;

import com.openplan.backend.dashboard.service.port.DashboardPlanReader;
import com.openplan.backend.dashboard.service.port.TodayBlockRow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link DashboardPlanReader} 잠정 구현. weekly_plans·plan_blocks·validation_issues를 JdbcTemplate로만
 * 접촉한다 — JPA 엔티티는 weeklyplan 패키지 소유라 침범하지 않는다({@code JdbcWeeklyPlanTotalsRecalculator}
 * 선례). 요일·zone 변환은 SQL의 {@code AT TIME ZONE} 문자열 조립(인젝션·DST 오분류 위험) 대신 Instant 경계를
 * Java에서 계산해 파라미터로 넘기는 방식을 쓴다(project 패키지 선례와 동일한 이유는 아니지만 같은 안전 원칙).
 */
@Component
public class JdbcDashboardPlanReader implements DashboardPlanReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDashboardPlanReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int totalPlannedMinutes(UUID userId, LocalDate weekStartDate) {
        try {
            Integer minutes = jdbcTemplate.queryForObject(
                    "SELECT total_planned_minutes FROM weekly_plans WHERE user_id = ? AND week_start_date = ?",
                    Integer.class, userId, weekStartDate);
            return minutes == null ? 0 : minutes;
        } catch (EmptyResultDataAccessException ex) {
            return 0; // 그 주 weekly_plans 행 미생성 — 배치 없음과 동치
        }
    }

    @Override
    public Map<DayOfWeek, Integer> plannedMinutesByWeekday(
            UUID userId, LocalDate weekStart, LocalDate weekEnd, ZoneId zone) {
        Instant from = weekStart.atStartOfDay(zone).toInstant();
        Instant to = weekEnd.plusDays(1).atStartOfDay(zone).toInstant();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT pb.start_at, pb.end_at
                  FROM plan_blocks pb
                  JOIN weekly_plans w ON w.weekly_plan_id = pb.weekly_plan_id
                 WHERE w.user_id = ? AND pb.start_at >= ? AND pb.start_at < ?
                """, userId, Timestamp.from(from), Timestamp.from(to));

        Map<DayOfWeek, Integer> byWeekday = new EnumMap<>(DayOfWeek.class);
        for (Map<String, Object> row : rows) {
            Instant startAt = ((Timestamp) row.get("start_at")).toInstant();
            Instant endAt = ((Timestamp) row.get("end_at")).toInstant();
            DayOfWeek weekday = startAt.atZone(zone).getDayOfWeek(); // zone 기준 요일(rule 엔진 V3와 동일 원칙)
            int minutes = (int) Duration.between(startAt, endAt).toMinutes();
            byWeekday.merge(weekday, minutes, Integer::sum);
        }
        return byWeekday;
    }

    @Override
    public List<TodayBlockRow> todayBlocks(UUID userId, LocalDate today, ZoneId zone) {
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();

        return jdbcTemplate.query("""
                SELECT pb.plan_block_id, pb.task_id, pb.block_type, pb.start_at, pb.end_at, pb.status,
                       COALESCE(t.title, s.title)                         AS title,
                       COALESCE(t.estimated_minutes, s.estimated_minutes) AS estimated_minutes
                  FROM plan_blocks pb
                  JOIN weekly_plans w ON w.weekly_plan_id = pb.weekly_plan_id
                  LEFT JOIN tasks t     ON pb.block_type = 'TASK'     AND t.task_id = pb.task_id
                  LEFT JOIN schedules s ON pb.block_type = 'SCHEDULE' AND s.schedule_id = pb.schedule_id
                 WHERE w.user_id = ? AND pb.start_at >= ? AND pb.start_at < ?
                 ORDER BY pb.start_at ASC, pb.plan_block_id ASC
                """,
                (rs, rowNum) -> new TodayBlockRow(
                        UUID.fromString(rs.getString("plan_block_id")),
                        rs.getString("task_id") == null ? null : UUID.fromString(rs.getString("task_id")),
                        rs.getString("title"),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant(),
                        rs.getObject("estimated_minutes") == null ? null : rs.getInt("estimated_minutes"),
                        "COMPLETED".equals(rs.getString("status")),
                        "TASK".equals(rs.getString("block_type"))),
                userId, Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    public Map<String, Long> openIssueCounts(UUID userId, LocalDate weekStartDate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT vi.issue_type, COUNT(*) AS cnt
                  FROM validation_issues vi
                  JOIN weekly_plans w ON w.weekly_plan_id = vi.weekly_plan_id
                 WHERE w.user_id = ? AND w.week_start_date = ? AND vi.resolution_status = 'OPEN'
                 GROUP BY vi.issue_type
                """, userId, weekStartDate);

        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("issue_type"), ((Number) row.get("cnt")).longValue());
        }
        return counts;
    }
}
