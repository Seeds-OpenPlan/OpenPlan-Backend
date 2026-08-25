package com.openplan.backend.notification.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 알림 판정에 필요한 <b>다른 도메인의 사실</b>을 읽는 포트 (ADR-0014).
 *
 * <p>알림은 태스크·주간계획·수행이력·문의를 모두 들여다봐야 하는데, 그 레포지토리들을 직접 물면
 * 이 도메인이 네 도메인에 묶인다. 포트 하나로 좁혀 <b>읽기만</b> 한다 — 판정도 생성도 하지 않는다.
 */
public interface NotificationSourceReader {

    /** 사용자 timezone. 프로필 부재·미설정 시 {@code Asia/Seoul}(Q-G). */
    ZoneId zoneOf(UUID userId);

    /** DEADLINE_SOON 후보 — 미완료 + {@code today ≤ due_date ≤ today+3}(결정문 §5.1 D-3). */
    List<DeadlineTask> deadlineSoonTasks(UUID userId, LocalDate today);

    /** TODAY_TASKS 후보 — 오늘(zone) 배치된 미완료 TASK 블록. 0건이면 미생성이라 count 로 돌려준다. */
    Optional<TodayTasks> todayTasks(UUID userId, LocalDate today);

    /** PLAN_UNSAVED 후보 — 이번 주 계획이 존재하고 DRAFT 인 경우의 planId(ADR-0008). */
    Optional<UUID> draftWeeklyPlanId(UUID userId, LocalDate weekStart);

    /** RETROSPECT 후보 — 지난주 구간 {@code [weekStart−7, weekStart)} 의 본인 수행 기록 수. */
    int lastWeekExecutionLogCount(UUID userId, LocalDate weekStart);

    /** SUPPORT_ANSWERED 후보 — 답변이 등록된 본인 문의(NFR-030 스코핑). */
    List<AnsweredTicket> answeredTickets(UUID userId);

    record DeadlineTask(UUID taskId, String title, LocalDate dueDate) {
    }

    record TodayTasks(UUID weeklyPlanId, int count) {
    }

    record AnsweredTicket(UUID ticketId, String title) {
    }
}
