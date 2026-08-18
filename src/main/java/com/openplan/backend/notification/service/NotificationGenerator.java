package com.openplan.backend.notification.service;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.notification.domain.Notification;
import com.openplan.backend.notification.domain.NotificationSetting;
import com.openplan.backend.notification.domain.NotificationType;
import com.openplan.backend.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 알림 지연 생성 (ADR-0014 결정 ①·③).
 *
 * <p><b>스케줄러가 없다.</b> 알림은 조회 진입 시 판정해 만든다 — ADR-0006 이 스케줄러를 금지한
 * 선례를 따른다. 판정 조건은 전부 <b>기존 행 + 현재 상태</b>만으로 결정되므로 같은 입력에 같은 결과이고,
 * 재판정은 no-op 이다.
 *
 * <p><b>REQUIRES_NEW 로 분리한다.</b> 판정이 실패해도 이미 쌓인 알림은 보여야 하기 때문이다 —
 * 조회 트랜잭션에 얹으면 판정 실패가 알림 센터 자체를 못 열게 만든다.
 *
 * <p><b>꺼진 유형은 판정 자체를 건너뛴다</b>(ST-B1-12 AC1). 만들고 거르는 게 아니라 안 만든다 —
 * 껐다 켜도 그 사이의 알림은 되살아나지 않는다.
 */
@Service
public class NotificationGenerator {

    private final NotificationRepository repository;
    private final NotificationSettingService settingService;
    private final NotificationSourceReader reader;
    private final UserClock clock;

    public NotificationGenerator(NotificationRepository repository,
                                 NotificationSettingService settingService,
                                 NotificationSourceReader reader,
                                 UserClock clock) {
        this.repository = repository;
        this.settingService = settingService;
        this.reader = reader;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateFor(UUID userId) {
        Map<NotificationType, NotificationSetting> settings = settingService.ensureSettings(userId);
        LocalDate today = clock.todayOf(userId);
        ZoneId zone = reader.zoneOf(userId);
        LocalDate weekStart = today.with(DayOfWeek.MONDAY); // 주 시작 = 월요일(weekly_plans 기존 정의)

        deadlineSoon(userId, settings, today, zone);
        todayTasks(userId, settings, today, zone);
        planUnsaved(userId, settings, weekStart, zone);
        retrospect(userId, settings, weekStart, zone);
        supportAnswered(userId, settings);
    }

    /** 태스크당 1건. 중복 창은 "임박 창의 시작"({@code due_date−3일}의 zone 자정)부터다 — 마감을 미루면 창이 새로 열린다. */
    private void deadlineSoon(UUID userId, Map<NotificationType, NotificationSetting> settings,
                              LocalDate today, ZoneId zone) {
        if (disabled(settings, NotificationType.DEADLINE_SOON)) {
            return;
        }
        UUID settingId = settings.get(NotificationType.DEADLINE_SOON).getNotificationSettingId();
        for (NotificationSourceReader.DeadlineTask t : reader.deadlineSoonTasks(userId, today)) {
            var windowStart = t.dueDate().minusDays(3).atStartOfDay(zone).toInstant();
            boolean already = repository
                    .existsByUserIdAndNotificationTypeAndRelatedTaskIdAndCreatedAtGreaterThanEqual(
                            userId, NotificationType.DEADLINE_SOON, t.taskId(), windowStart);
            if (already) {
                continue;
            }
            repository.save(Notification.forTask(userId, settingId, NotificationType.DEADLINE_SOON,
                    NotificationCopy.deadlineSoon(t.title(), t.dueDate()),
                    NotificationRoutes.task(t.taskId()), t.taskId(), clock.now()));
        }
    }

    /** 사용자 × 날짜당 1회. */
    private void todayTasks(UUID userId, Map<NotificationType, NotificationSetting> settings,
                            LocalDate today, ZoneId zone) {
        if (disabled(settings, NotificationType.TODAY_TASKS)) {
            return;
        }
        Optional<NotificationSourceReader.TodayTasks> found = reader.todayTasks(userId, today);
        if (found.isEmpty()) {
            return; // 0건이면 미생성 — 조치할 것이 없는 알림은 만들지 않는다
        }
        var since = today.atStartOfDay(zone).toInstant();
        if (repository.existsByUserIdAndNotificationTypeAndCreatedAtGreaterThanEqual(
                userId, NotificationType.TODAY_TASKS, since)) {
            return;
        }
        NotificationSourceReader.TodayTasks t = found.get();
        repository.save(Notification.forWeeklyPlan(userId,
                settings.get(NotificationType.TODAY_TASKS).getNotificationSettingId(),
                NotificationType.TODAY_TASKS, NotificationCopy.todayTasks(t.count()),
                NotificationRoutes.weekly(), t.weeklyPlanId(), clock.now()));
    }

    /** 사용자 × 주당 1회. 주중 CONFIRMED→DRAFT 재복귀도 재알림하지 않는다. */
    private void planUnsaved(UUID userId, Map<NotificationType, NotificationSetting> settings,
                             LocalDate weekStart, ZoneId zone) {
        if (disabled(settings, NotificationType.PLAN_UNSAVED)) {
            return;
        }
        Optional<UUID> planId = reader.draftWeeklyPlanId(userId, weekStart);
        if (planId.isEmpty()) {
            return; // 계획 행이 없는 주는 미발생 — "미저장"이 아니라 미배치의 영역이다
        }
        var since = weekStart.atStartOfDay(zone).toInstant();
        if (repository.existsByUserIdAndNotificationTypeAndCreatedAtGreaterThanEqual(
                userId, NotificationType.PLAN_UNSAVED, since)) {
            return;
        }
        repository.save(Notification.forWeeklyPlan(userId,
                settings.get(NotificationType.PLAN_UNSAVED).getNotificationSettingId(),
                NotificationType.PLAN_UNSAVED, NotificationCopy.planUnsaved(),
                NotificationRoutes.weekly(), planId.get(), clock.now()));
    }

    /** 새 주 진입 후 1회. 기록이 없는 사용자에게는 만들지 않는다 — 빈 통계로 보내는 알림은 조치 불가다. */
    private void retrospect(UUID userId, Map<NotificationType, NotificationSetting> settings,
                            LocalDate weekStart, ZoneId zone) {
        if (disabled(settings, NotificationType.RETROSPECT)) {
            return;
        }
        int count = reader.lastWeekExecutionLogCount(userId, weekStart);
        if (count == 0) {
            return;
        }
        var since = weekStart.atStartOfDay(zone).toInstant();
        if (repository.existsByUserIdAndNotificationTypeAndCreatedAtGreaterThanEqual(
                userId, NotificationType.RETROSPECT, since)) {
            return;
        }
        repository.save(Notification.standalone(userId,
                settings.get(NotificationType.RETROSPECT).getNotificationSettingId(),
                NotificationType.RETROSPECT, NotificationCopy.retrospect(count),
                NotificationRoutes.statistics(), clock.now()));
    }

    /** 문의당 1회 — 창이 없다. 답변은 한 번 등록되면 사실이 바뀌지 않는다. */
    private void supportAnswered(UUID userId, Map<NotificationType, NotificationSetting> settings) {
        if (disabled(settings, NotificationType.SUPPORT_ANSWERED)) {
            return;
        }
        UUID settingId = settings.get(NotificationType.SUPPORT_ANSWERED).getNotificationSettingId();
        for (NotificationSourceReader.AnsweredTicket t : reader.answeredTickets(userId)) {
            if (repository.existsByUserIdAndNotificationTypeAndRelatedSupportTicketId(
                    userId, NotificationType.SUPPORT_ANSWERED, t.ticketId())) {
                continue;
            }
            repository.save(Notification.forSupportTicket(userId, settingId,
                    NotificationType.SUPPORT_ANSWERED, NotificationCopy.supportAnswered(t.title()),
                    NotificationRoutes.supportTicket(t.ticketId()), t.ticketId(), clock.now()));
        }
    }

    private boolean disabled(Map<NotificationType, NotificationSetting> settings, NotificationType type) {
        return !settings.get(type).isEnabled();
    }
}
