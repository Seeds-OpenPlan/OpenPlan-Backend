package com.openplan.backend.push.service;

import com.openplan.backend.push.support.FakePushConfig;
import com.openplan.backend.push.support.FakeWebPushSender;
import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 선택 로직 통합 테스트 (ADR-0015 결정 ②·③) — 실제 PostgreSQL + {@link PushReminderDispatcher#runOnce()}
 * 직접 호출(스케줄러 대기 없음). 시계는 {@link FixedClockConfig}로 고정 — {@code now}가 2026-07-15T00:00:00Z
 * (Asia/Seoul 09:00, 수요일)이라 창은 절대시각 {@code (00:05Z, 00:10Z]}로 고정된다.
 *
 * <p>발송은 {@link FakeWebPushSender}가 가로챈다({@link FakePushConfig}가 VAPID를 "설정됨"으로 고정) —
 * 네트워크·실제 암호화를 타지 않고 "누구에게 보냈는가"만 기록한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class, FakePushConfig.class})
class PushReminderDispatcherTest {

    private static final UUID USER = UUID.fromString("ffff0015-0000-0000-0000-000000000001");

    private static final Instant NOW = FixedClockConfig.FIXED_NOW;              // 2026-07-15T00:00:00Z
    private static final Instant IN_WINDOW = NOW.plusSeconds(7 * 60);           // now+7m ∈ (now+5m, now+10m]
    private static final Instant IN_WINDOW_LATER = NOW.plusSeconds(9 * 60);     // now+9m — 재배치 후 재발송 확인용
    private static final Instant OUT_OF_WINDOW = NOW.plusSeconds(30 * 60);      // 아직 창 밖(너무 이르다)
    private static final Instant ALREADY_STARTED = NOW.minusSeconds(60);        // 이미 시작(제외 대상)

    @Autowired
    private PushReminderDispatcher dispatcher;
    @Autowired
    private FakeWebPushSender sender;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        sender.reset();
        seedUser(USER);
        jdbc.update("DELETE FROM push_deliveries WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM push_settings WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id = ?)", USER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM schedules WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM fixed_schedules WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN (SELECT project_id FROM projects WHERE user_id = ?)", USER);
        jdbc.update("DELETE FROM projects WHERE user_id = ?", USER);
        insertSubscription(USER, "https://push.example.net/ep-" + USER);
    }

    // ---------------------------------------------------------------- TASK

    @Test
    @DisplayName("TASK: DRAFT 주간계획의 태스크 블록은 보내지 않는다")
    void draftPlanTaskIsNotSent() {
        setToggles(USER, true, false, false);
        UUID plan = insertWeeklyPlan(USER, "DRAFT");
        UUID task = insertTask(USER, "보고서");
        insertPlanBlock(plan, "TASK", task, null, IN_WINDOW);

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    @Test
    @DisplayName("TASK: CONFIRMED 주간계획의 태스크 블록은 창 안에서 보낸다")
    void confirmedPlanTaskIsSent() {
        setToggles(USER, true, false, false);
        UUID plan = insertWeeklyPlan(USER, "CONFIRMED");
        UUID task = insertTask(USER, "보고서");
        insertPlanBlock(plan, "TASK", task, null, IN_WINDOW);

        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(1);
        assertThat(sender.calls().get(0).payloadJson()).contains("\"title\":\"보고서\"");
    }

    @Test
    @DisplayName("TASK: 토글이 꺼져 있으면 CONFIRMED 라도 보내지 않는다")
    void taskToggleOffSendsNothing() {
        setToggles(USER, false, false, false);
        UUID plan = insertWeeklyPlan(USER, "CONFIRMED");
        UUID task = insertTask(USER, "보고서");
        insertPlanBlock(plan, "TASK", task, null, IN_WINDOW);

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    @Test
    @DisplayName("TASK: 이미 시작한 블록(start_at ≤ now)은 보내지 않는다")
    void alreadyStartedTaskIsNotSent() {
        setToggles(USER, true, false, false);
        UUID plan = insertWeeklyPlan(USER, "CONFIRMED");
        UUID task = insertTask(USER, "보고서");
        insertPlanBlock(plan, "TASK", task, null, ALREADY_STARTED);

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    @Test
    @DisplayName("TASK: 창 밖(아직 10분보다 더 이르다)이면 보내지 않는다")
    void outOfWindowTaskIsNotSent() {
        setToggles(USER, true, false, false);
        UUID plan = insertWeeklyPlan(USER, "CONFIRMED");
        UUID task = insertTask(USER, "보고서");
        insertPlanBlock(plan, "TASK", task, null, OUT_OF_WINDOW);

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    // ---------------------------------------------------------------- SCHEDULE

    @Test
    @DisplayName("SCHEDULE: 블록을 옮기면 블록 시각을 쓴다 — schedules.start_at 은 그대로 둬도 블록 시각으로 보낸다")
    void scheduleUsesBlockTimeNotScheduleTime() {
        setToggles(USER, false, false, true);
        UUID plan = insertWeeklyPlan(USER, "DRAFT"); // 일정은 확정 여부와 무관
        UUID schedule = insertSchedule(USER, "회의", OUT_OF_WINDOW); // schedules.start_at = 창 밖
        insertPlanBlock(plan, "SCHEDULE", null, schedule, IN_WINDOW); // 블록만 창 안으로 재배치

        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(1);
        assertThat(sender.calls().get(0).payloadJson()).contains("\"title\":\"회의\"");
    }

    @Test
    @DisplayName("SCHEDULE: 일정 상태가 INACTIVE 면 보내지 않는다")
    void inactiveScheduleIsNotSent() {
        setToggles(USER, false, false, true);
        UUID plan = insertWeeklyPlan(USER, "DRAFT");
        UUID schedule = insertSchedule(USER, "회의", IN_WINDOW);
        jdbc.update("UPDATE schedules SET status = 'INACTIVE' WHERE schedule_id = ?", schedule);
        insertPlanBlock(plan, "SCHEDULE", null, schedule, IN_WINDOW);

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    // ---------------------------------------------------------------- FIXED_SCHEDULE

    @Test
    @DisplayName("FIXED_SCHEDULE: 그 주 예외가 있으면 보내지 않는다 (PLAN-33)")
    void weekExceptionSkipsOccurrence() {
        setToggles(USER, false, true, false);
        // FIXED_NOW = 2026-07-15(수) 09:10 Asia/Seoul 이 창 안 — 그 주 시작(월)은 07-13.
        UUID fs = insertFixedSchedule(USER, "수업", "WED", LocalTime.of(9, 10), null, null);
        insertWeekException(fs, LocalDate.of(2026, 7, 13));

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    @Test
    @DisplayName("FIXED_SCHEDULE: 예외가 없으면 창 안의 회차를 보낸다")
    void occurrenceWithoutExceptionIsSent() {
        setToggles(USER, false, true, false);
        insertFixedSchedule(USER, "수업", "WED", LocalTime.of(9, 10), null, null);

        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(1);
        assertThat(sender.calls().get(0).payloadJson()).contains("\"title\":\"수업\"");
    }

    @Test
    @DisplayName("FIXED_SCHEDULE: end_date 가 그 날짜보다 이전이면 보내지 않는다")
    void endDateBeforeOccurrenceExcludesIt() {
        setToggles(USER, false, true, false);
        insertFixedSchedule(USER, "수업", "WED", LocalTime.of(9, 10), null, LocalDate.of(2026, 7, 14));

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    @Test
    @DisplayName("FIXED_SCHEDULE: start_date 가 그 날짜보다 이후면 보내지 않는다")
    void startDateAfterOccurrenceExcludesIt() {
        setToggles(USER, false, true, false);
        insertFixedSchedule(USER, "수업", "WED", LocalTime.of(9, 10), LocalDate.of(2026, 7, 16), null);

        dispatcher.runOnce();

        assertThat(sender.calls()).isEmpty();
    }

    // ---------------------------------------------------------------- 멱등·재발송

    @Test
    @DisplayName("멱등: 두 번 돌려도 한 번만 보낸다 — 블록을 옮겨 start_at 이 바뀌면 다시 보낸다")
    void dedupeThenResendsAfterStartAtChanges() {
        setToggles(USER, true, false, false);
        UUID plan = insertWeeklyPlan(USER, "CONFIRMED");
        UUID task = insertTask(USER, "보고서");
        UUID block = insertPlanBlock(plan, "TASK", task, null, IN_WINDOW);

        dispatcher.runOnce();
        dispatcher.runOnce();
        assertThat(sender.calls()).as("같은 start_at 재판정은 no-op").hasSize(1);

        jdbc.update("UPDATE plan_blocks SET start_at = ? WHERE plan_block_id = ?",
                Timestamp.from(IN_WINDOW_LATER), block);
        dispatcher.runOnce();

        assertThat(sender.calls()).as("새 시작에는 다시 보낸다").hasSize(2);
    }

    // ---------------------------------------------------------------- 픽스처

    private void seedUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
                VALUES (?, ?, 'x', 'LOCAL', true, 'ACTIVE') ON CONFLICT (user_id) DO NOTHING
                """, id, id + "@test.local");
        jdbc.update("""
                INSERT INTO user_profiles (profile_id, user_id, name, purpose, timezone, week_start_day)
                VALUES (?, ?, '테스트', '테스트', 'Asia/Seoul', 'MON') ON CONFLICT (user_id) DO NOTHING
                """, UUID.randomUUID(), id);
    }

    private void setToggles(UUID userId, boolean task, boolean fixedSchedule, boolean schedule) {
        jdbc.update("""
                INSERT INTO push_settings (user_id, task_enabled, fixed_schedule_enabled, schedule_enabled)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET task_enabled = EXCLUDED.task_enabled,
                    fixed_schedule_enabled = EXCLUDED.fixed_schedule_enabled,
                    schedule_enabled = EXCLUDED.schedule_enabled
                """, userId, task, fixedSchedule, schedule);
    }

    private void insertSubscription(UUID userId, String endpoint) {
        jdbc.update("""
                INSERT INTO push_subscriptions (push_subscription_id, user_id, endpoint, p256dh, auth, platform)
                VALUES (?, ?, ?, 'test-p256dh', 'test-auth', 'ANDROID_APP')
                """, UUID.randomUUID(), userId, endpoint);
    }

    private UUID insertWeeklyPlan(UUID userId, String status) {
        UUID id = UUID.randomUUID();
        LocalDate weekStart = LocalDate.of(2026, 7, 13);
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, ?, NULL, 0, ?)
                """, id, userId, weekStart, weekStart.plusDays(6), status, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID userId, String title) {
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, '프로젝트', NULL, NULL, 'IN_PROGRESS', NULL, NULL, 0, ?)
                """, project, userId, OffsetDateTime.now(ZoneOffset.UTC));
        UUID task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, 30, NULL, NULL, 'UNASSIGNED', 0, ?)
                """, task, project, title, OffsetDateTime.now(ZoneOffset.UTC));
        return task;
    }

    private UUID insertSchedule(UUID userId, String title, Instant startAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (schedule_id, user_id, title, estimated_minutes, priority,
                                       start_at, end_at, memo, status, version, created_at)
                VALUES (?, ?, ?, 30, NULL, ?, ?, NULL, 'ACTIVE', 0, ?)
                """, id, userId, title, Timestamp.from(startAt), Timestamp.from(startAt.plusSeconds(1800)),
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertPlanBlock(UUID weeklyPlanId, String blockType, UUID taskId, UUID scheduleId, Instant startAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id, block_type,
                                         start_at, end_at, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?)
                """, id, weeklyPlanId, taskId, scheduleId, blockType,
                Timestamp.from(startAt), Timestamp.from(startAt.plusSeconds(1800)),
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertFixedSchedule(UUID userId, String title, String weekday, LocalTime startTime,
                                     LocalDate startDate, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fixed_schedules (fixed_schedule_id, user_id, connection_id, title, weekday,
                                             start_time, end_time, start_date, end_date, recurrence_rule,
                                             source, status, version, created_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, 'MANUAL', 'ACTIVE', 0, ?)
                """, id, userId, title, weekday, startTime, startTime.plusHours(1), startDate, endDate,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private void insertWeekException(UUID fixedScheduleId, LocalDate weekStartDate) {
        jdbc.update("""
                INSERT INTO fixed_schedule_week_exceptions (exception_id, fixed_schedule_id, week_start_date)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), fixedScheduleId, weekStartDate);
    }
}
