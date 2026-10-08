package com.openplan.backend.push.service;

import com.openplan.backend.common.Weekday;

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
 */
public interface PushTargetReader {

    /**
     * TASK 후보 — {@code plan_blocks.start_at}이 {@code (lowerExclusive, upperInclusive]}에 들고,
     * 블록 SCHEDULED·소속 weekly_plan CONFIRMED·사용자 task 토글 ON·구독 ≥1인 것만(ADR-0015 결정 ②).
     */
    List<PushTarget> taskTargets(Instant lowerExclusive, Instant upperInclusive);

    /** SCHEDULE 후보 — 블록 SCHEDULED·일정 ACTIVE·사용자 schedule 토글 ON·구독 ≥1(계획 확정 여부 무관). */
    List<PushTarget> scheduleTargets(Instant lowerExclusive, Instant upperInclusive);

    /** FIXED_SCHEDULE 후보 — ACTIVE·사용자 fixed_schedule 토글 ON·구독 ≥1인 고정 일정 전체(날짜 필터 없음). */
    List<FixedScheduleCandidate> fixedScheduleCandidates();

    /** 주어진 고정 일정들의 주차 예외를 한 번에 읽는다(N+1 방지) — {@code fixedScheduleId → 예외 주 시작일 집합}. */
    Map<UUID, Set<LocalDate>> weekExceptionsOf(List<UUID> fixedScheduleIds);

    /** TASK/SCHEDULE 공용 후보 1건. {@code targetId}는 {@code plan_block_id}. */
    record PushTarget(UUID userId, UUID targetId, Instant startAt, String title) {
    }

    /** 고정 일정 후보 1건 — 회차 전개는 디스패처가 사용자 zone·weekStartDay로 수행한다. */
    record FixedScheduleCandidate(UUID fixedScheduleId, UUID userId, String title, Weekday weekday,
                                  LocalTime startTime, LocalDate startDate, LocalDate endDate) {
    }
}
