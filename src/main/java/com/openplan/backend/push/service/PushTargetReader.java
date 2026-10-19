package com.openplan.backend.push.service;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.push.domain.PushLeadTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 발송 대상 판정에 필요한 <b>다른 도메인의 사실</b>을 읽는 포트 (ADR-0015 결정 ②). TASK/SCHEDULE은
 * set-based SQL로 창(window)에 바로 걸러 받고, FIXED_SCHEDULE은 요일 반복이라 SQL로 "그 날짜의 회차"를
 * 직접 거를 수 없어 후보(활성 고정 일정)만 받아 디스패처가 사용자 zone으로 회차를 펼친다.
 *
 * <p><b>창은 시작 시각이 아니라 발송 시각에 건다</b> — 발송 시각 = 시작 − 사용자가 고른 알림 시간
 * ({@code push_settings.lead_minutes}, 2026-10-11 개정). 사용자마다 시간이 달라 하나의 시작 범위로는
 * 거를 수 없다. 설정 행이 없는 사용자는 기본값(전부 켜짐 · {@link PushLeadTime#DEFAULT_MINUTES}분)으로 읽는다.
 */
public interface PushTargetReader {

    /**
     * TASK 후보 — 발송 시각({@code plan_blocks.start_at − lead})이 {@code (sendLowerExclusive, sendUpperInclusive]}에
     * 들고, 블록 SCHEDULED·소속 weekly_plan CONFIRMED·사용자 task 토글 ON·구독 ≥1인 것만(ADR-0015 결정 ②).
     */
    List<PushTarget> taskTargets(Instant sendLowerExclusive, Instant sendUpperInclusive);

    /** SCHEDULE 후보 — 블록 SCHEDULED·일정 ACTIVE·사용자 schedule 토글 ON·구독 ≥1(계획 확정 여부 무관). 창은 위와 같다. */
    List<PushTarget> scheduleTargets(Instant sendLowerExclusive, Instant sendUpperInclusive);

    /** FIXED_SCHEDULE 후보 — ACTIVE·사용자 fixed_schedule 토글 ON·구독 ≥1인 고정 일정 전체(날짜 필터 없음). */
    List<FixedScheduleCandidate> fixedScheduleCandidates();

    /** 주어진 고정 일정들의 주차 예외를 한 번에 읽는다(N+1 방지) — {@code fixedScheduleId → 예외 주 시작일 집합}. */
    Map<UUID, Set<LocalDate>> weekExceptionsOf(List<UUID> fixedScheduleIds);

    /** TASK/SCHEDULE 공용 후보 1건. {@code targetId}는 {@code plan_block_id}. {@code leadMinutes}는 알림 문구에 쓴다. */
    record PushTarget(UUID userId, UUID targetId, Instant startAt, String title, int leadMinutes) {
    }

    /** 고정 일정 후보 1건 — 회차 전개는 디스패처가 사용자 zone·weekStartDay·알림 시간으로 수행한다. */
    record FixedScheduleCandidate(UUID fixedScheduleId, UUID userId, String title, Weekday weekday,
                                  LocalTime startTime, LocalDate startDate, LocalDate endDate, int leadMinutes) {
    }
}
