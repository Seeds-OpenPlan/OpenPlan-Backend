package com.openplan.backend.push.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openplan.backend.common.WeekRange;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.push.domain.PushDeliveryTargetType;
import com.openplan.backend.push.domain.PushSubscription;
import com.openplan.backend.push.infra.VapidProperties;
import com.openplan.backend.push.repository.PushDeliveryRepository;
import com.openplan.backend.push.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 시작 10분 전 푸시 발송기 (ADR-0015) — ADR-0006 스케줄러 금지를 이 한 곳에서만 푼다(결정 ①).
 *
 * <p><b>창(window)과 선행시간 환산</b>: 발송 시각 = 시작 − 10분, 매 틱 창 = {@code (now−5분, now]}
 * (결정 ②). 이를 {@code start_at}의 범위로 바꾸면 {@code (now+5분, now+10분]}과 같다 — "이미 시작한
 * 대상 제외"(start_at ≤ now)는 이 범위 안에 자동으로 포함된다(now+5분 > now).
 *
 * <p><b>예외는 대상 1건 단위로 가둔다</b> — 한 사용자·한 블록의 발송 실패가 같은 틱의 다른 대상까지
 * 막으면 안 된다({@link #dispatch}). 틱 전체를 감싸는 바깥 try/catch({@link #tick})는 대상 조회 자체가
 * 실패하는 경우(DB 일시 장애 등)에 대한 최후 방어선이다 — {@code @Scheduled} 메서드가 예외를 던지면
 * Spring이 그 트리거의 다음 실행을 재등록하지 않아(스케줄러 영구 정지), 예외가 여기를 벗어나면 안 된다.
 */
@Component
public class PushReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushReminderDispatcher.class);

    private static final Duration LEAD_TIME = Duration.ofMinutes(10); // 사용자 결정 — 고정, 선택 불가
    private static final Duration WINDOW = Duration.ofMinutes(5);     // 재배포로 놓친 회차를 따라잡는 여유(결정 ②)

    private final VapidProperties vapidProperties;
    private final PushTargetReader targetReader;
    private final PushSubscriptionRepository subscriptionRepository;
    private final PushDeliveryRepository deliveryRepository;
    private final WebPushSender sender;
    private final UserClock clock;
    private final ObjectMapper objectMapper;

    /** VAPID 미설정 로그를 1회로 줄인다 — 매 틱(1분)마다 같은 경고를 쌓으면 로그가 이 사실로만 찬다. */
    private final AtomicBoolean loggedVapidMissing = new AtomicBoolean(false);

    public PushReminderDispatcher(VapidProperties vapidProperties, PushTargetReader targetReader,
                                  PushSubscriptionRepository subscriptionRepository,
                                  PushDeliveryRepository deliveryRepository, WebPushSender sender,
                                  UserClock clock, ObjectMapper objectMapper) {
        this.vapidProperties = vapidProperties;
        this.targetReader = targetReader;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.sender = sender;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("push dispatcher tick failed — 다음 틱에서 재시도", e);
        }
    }

    /** 스케줄러를 기다리지 않는 테스트 진입점. 운영 경로는 {@link #tick()}을 거쳐 이 메서드로 온다. */
    public void runOnce() {
        if (!vapidProperties.isConfigured()) {
            if (loggedVapidMissing.compareAndSet(false, true)) {
                log.info("VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY/VAPID_SUBJECT 중 하나 이상이 비어 있어 "
                        + "푸시 발송기를 비활성 상태로 둡니다(ADR-0015 결정 ⑤)");
            }
            return;
        }
        Instant now = clock.now();
        Instant lowerExclusive = now.plus(LEAD_TIME).minus(WINDOW);
        Instant upperInclusive = now.plus(LEAD_TIME);

        for (PushTargetReader.PushTarget t : targetReader.taskTargets(lowerExclusive, upperInclusive)) {
            dispatch(t.userId(), PushDeliveryTargetType.TASK, t.targetId(), t.startAt(), t.title());
        }
        for (PushTargetReader.PushTarget t : targetReader.scheduleTargets(lowerExclusive, upperInclusive)) {
            dispatch(t.userId(), PushDeliveryTargetType.SCHEDULE, t.targetId(), t.startAt(), t.title());
        }
        dispatchFixedSchedules(lowerExclusive, upperInclusive);
    }

    /**
     * 고정 일정은 요일 반복이라 SQL로 "그 날짜의 회차"를 바로 거를 수 없다 — 활성 후보를 받아 사용자
     * zone에서 날짜를 펼치고(결정 ②), 그 날짜가 창에 드는 절대 시각을 만든 뒤에야 판정할 수 있다.
     * 창이 사용자 zone의 자정을 넘는 경우를 대비해 창의 시작·끝 각각의 zone-local 날짜를 모두 훑는다.
     */
    private void dispatchFixedSchedules(Instant lowerExclusive, Instant upperInclusive) {
        List<PushTargetReader.FixedScheduleCandidate> candidates = targetReader.fixedScheduleCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        List<UUID> ids = candidates.stream().map(PushTargetReader.FixedScheduleCandidate::fixedScheduleId).toList();
        Map<UUID, Set<LocalDate>> exceptionsByScheduleId = targetReader.weekExceptionsOf(ids);

        Map<UUID, List<PushTargetReader.FixedScheduleCandidate>> byUser = candidates.stream()
                .collect(Collectors.groupingBy(PushTargetReader.FixedScheduleCandidate::userId));

        for (Map.Entry<UUID, List<PushTargetReader.FixedScheduleCandidate>> entry : byUser.entrySet()) {
            UUID userId = entry.getKey();
            ZoneId zone = clock.zoneOf(userId);
            Weekday weekStartDay = clock.weekStartDayOf(userId);
            LocalDate dateAtLower = lowerExclusive.atZone(zone).toLocalDate();
            LocalDate dateAtUpper = upperInclusive.atZone(zone).toLocalDate();

            for (PushTargetReader.FixedScheduleCandidate c : entry.getValue()) {
                Set<LocalDate> exceptions = exceptionsByScheduleId.getOrDefault(c.fixedScheduleId(), Set.of());
                for (LocalDate date = dateAtLower; !date.isAfter(dateAtUpper); date = date.plusDays(1)) {
                    Instant occurrenceStart = occurrenceStartIfInWindow(c, date, zone, weekStartDay, exceptions,
                            lowerExclusive, upperInclusive);
                    if (occurrenceStart != null) {
                        dispatch(userId, PushDeliveryTargetType.FIXED_SCHEDULE, c.fixedScheduleId(),
                                occurrenceStart, c.title());
                    }
                }
            }
        }
    }

    /** @return 그 날짜의 회차가 창에 들면 절대 시작 시각, 아니면 null(요일 불일치·기간 밖·주차 예외·창 밖). */
    private Instant occurrenceStartIfInWindow(PushTargetReader.FixedScheduleCandidate c, LocalDate date,
                                              ZoneId zone, Weekday weekStartDay, Set<LocalDate> exceptions,
                                              Instant lowerExclusive, Instant upperInclusive) {
        if (!matchesWeekday(c.weekday(), date)) {
            return null;
        }
        if (c.startDate() != null && date.isBefore(c.startDate())) {
            return null;
        }
        if (c.endDate() != null && date.isAfter(c.endDate())) {
            return null;
        }
        LocalDate weekStart = WeekRange.of(date, weekStartDay).start();
        if (exceptions.contains(weekStart)) {
            return null; // PLAN-33 — 그 주 한정 제외(ADR-0011)
        }
        Instant occurrenceStart = date.atTime(c.startTime()).atZone(zone).toInstant();
        boolean inWindow = occurrenceStart.isAfter(lowerExclusive) && !occurrenceStart.isAfter(upperInclusive);
        return inWindow ? occurrenceStart : null;
    }

    /** {@link Weekday} 선언 순서(MON..SUN)가 {@link java.time.DayOfWeek#getValue()}(월=1..일=7)와 1:1(WeekRange와 동일 전제). */
    private boolean matchesWeekday(Weekday weekday, LocalDate date) {
        return date.getDayOfWeek().getValue() - 1 == weekday.ordinal();
    }

    /**
     * 멱등 게이트 → 구독 전체 발송. 대상 1건의 실패가 다른 대상에 번지지 않도록 여기서 가둔다
     * (ADR-0015 — 디스패처는 예외로 죽지 않는다).
     */
    private void dispatch(UUID userId, PushDeliveryTargetType type, UUID targetId, Instant startAt, String title) {
        try {
            boolean firstTime = deliveryRepository.tryMark(userId, type.name(), targetId, startAt, clock.now());
            if (!firstTime) {
                return; // 결정 ③ — 같은 대상·같은 시작은 한 번만
            }
            List<PushSubscription> subscriptions = subscriptionRepository.findByUserId(userId);
            if (subscriptions.isEmpty()) {
                return; // 조회(결정 ②의 EXISTS)와 발송 사이에 해지된 경우 — push_deliveries는 이미 찍혀 무해
            }
            String payload = buildPayload(type, targetId, title, startAt, userId);
            for (PushSubscription subscription : subscriptions) {
                WebPushSender.Outcome outcome = sender.send(subscription, payload);
                if (outcome == WebPushSender.Outcome.SUBSCRIPTION_GONE) {
                    subscriptionRepository.deleteByUserIdAndEndpoint(subscription.getUserId(), subscription.getEndpoint());
                }
            }
        } catch (RuntimeException e) {
            log.warn("push dispatch failed userId={} type={} targetId={}", userId, type, targetId, e);
        }
    }

    private String buildPayload(PushDeliveryTargetType type, UUID targetId, String title, Instant startAt,
                                UUID userId) {
        ZoneId zone = clock.zoneOf(userId);
        String time = DateTimeFormatter.ofPattern("HH:mm").format(startAt.atZone(zone));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", "10분 뒤 시작합니다 · " + time);
        payload.put("url", "/weekly");
        payload.put("tag", type.name().toLowerCase(Locale.ROOT) + ":" + targetId + ":" + startAt.getEpochSecond());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // LinkedHashMap<String,String/Long>만 담은 고정 구조 — 직렬화 실패는 설계상 불가능.
            throw new IllegalStateException("push payload 직렬화 실패", e);
        }
    }
}
