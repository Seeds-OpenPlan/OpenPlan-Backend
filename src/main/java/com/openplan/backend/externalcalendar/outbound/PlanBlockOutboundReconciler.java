package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.PlanBlockType;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 확정된 주간 계획의 태스크 블록을 외부와 맞춘다 (#69 계획 D3).
 *
 * <p><b>확정할 때만 부른다.</b> 블록을 건드리면 주간 계획이 자동으로 초안으로 돌아가므로
 * ({@code reopenToDraftIfConfirmed}), «확정» 은 곧 «이대로 하겠다» 는 사용자 의사 표시다.
 * 초안 단계의 드래그마다 내보내면 실제 캘린더가 흔들리고, 자동 배치·재계획 한 번에 수십 건이
 * API 로 나간다.
 *
 * <p>🔴 <b>블록 단위 훅은 쓸 수 없다.</b> 자동 배치가 블록을 지웠다 새 UUID 로 다시 만들기
 * 때문이다. 그래서 확정 시점에 <b>그 주의 블록 목록과 매핑을 견주어</b> 차이만 내보낸다.
 * 매핑의 키는 (주간계획, 태스크, 순번)이고, 그 셋은 재배치를 견딘다.
 */
@Service
public class PlanBlockOutboundReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlanBlockOutboundReconciler.class);

    private final PlanBlockRepository planBlockRepository;
    private final PlanBlockExternalRefRepository refRepository;
    private final ExternalCalendarConnectionRepository connectionRepository;
    private final OutboundCalendarOpRepository opRepository;
    private final JdbcTemplate jdbc;
    private final UserClock clock;

    public PlanBlockOutboundReconciler(PlanBlockRepository planBlockRepository,
                                       PlanBlockExternalRefRepository refRepository,
                                       ExternalCalendarConnectionRepository connectionRepository,
                                       OutboundCalendarOpRepository opRepository,
                                       JdbcTemplate jdbc, UserClock clock) {
        this.planBlockRepository = planBlockRepository;
        this.refRepository = refRepository;
        this.connectionRepository = connectionRepository;
        this.opRepository = opRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 확정 직후 — 이 주의 태스크 배치를 외부에 맞춘다.
     *
     * <p>🔴 <b>예외를 올리지 않는다.</b> 확정은 이미 성공했고, 외부로 내보낼 준비가 안 됐다고
     * 사용자의 확정이 되돌아가면 안 된다. 여기서 하는 일은 «적는 것» 뿐이라 실패할 거리도 적다.
     */
    public void onConfirmed(UUID userId, UUID weeklyPlanId) {
        try {
            reconcile(userId, weeklyPlanId);
        } catch (RuntimeException e) {
            log.warn("확정 후 외부 반영 적재 실패 — 다음 확정에서 다시 맞춘다: planId={}", weeklyPlanId, e);
        }
    }

    private void reconcile(UUID userId, UUID weeklyPlanId) {
        Optional<ExternalCalendarConnection> target = connectionRepository
                .findByUserIdOrderByConnectedAtAsc(userId).stream()
                .filter(ExternalCalendarConnection::canWrite)
                .filter(c -> c.getWriteCalendarId() != null && !c.getWriteCalendarId().isBlank())
                .findFirst();
        if (target.isEmpty()) {
            return;   // 모르면 쓰지 않는다.
        }
        ExternalCalendarConnection connection = target.get();
        String calendarId = connection.getWriteCalendarId();
        Instant now = clock.now();

        // ① 지금 이 주의 태스크 블록 — (태스크, 순번)으로 이름을 붙인다.
        //    순번은 **시작 시각 순**이라 같은 태스크의 두 세션이 매번 같은 이름을 받는다.
        Map<UUID, List<PlanBlock>> byTask = new HashMap<>();
        for (PlanBlock b : planBlockRepository.findByWeeklyPlanId(weeklyPlanId)) {
            if (b.getBlockType() == PlanBlockType.TASK && b.getTaskId() != null) {
                byTask.computeIfAbsent(b.getTaskId(), t -> new ArrayList<>()).add(b);
            }
        }
        Map<String, PlanBlock> wanted = new HashMap<>();
        for (var entry : byTask.entrySet()) {
            List<PlanBlock> blocks = new ArrayList<>(entry.getValue());
            blocks.sort(Comparator.comparing(PlanBlock::getStartAt).thenComparing(PlanBlock::getId));
            for (int i = 0; i < blocks.size(); i++) {
                wanted.put(entry.getKey() + "#" + i, blocks.get(i));
            }
        }

        // ② 지금 있는 매핑
        List<PlanBlockExternalRef> stored = refRepository.findByWeeklyPlanId(weeklyPlanId);
        Set<String> storedKeys = new HashSet<>();
        for (PlanBlockExternalRef ref : stored) {
            String key = ref.getTaskId() + "#" + ref.getSequence();
            storedKeys.add(key);
            // 🔴 다른 연동으로 나간 매핑은 그 연동의 것이다 — 이 연동의 자격증명·캘린더로 지목하지
            //    않는다(#79 리뷰와 같은 뿌리). 자리는 차지한 것으로 둬 새 UID 를 또 만들지 않는다.
            if (!connection.getId().equals(ref.getConnectionId())) {
                continue;
            }
            PlanBlock block = wanted.get(key);
            if (block == null) {
                // 그 자리가 없어졌다 — 태스크를 빼거나 세션을 줄였다.
                enqueueDelete(userId, connection, ref, calendarId, now);
                refRepository.delete(ref);
                continue;
            }
            String title = titleOf(block.getTaskId());
            if (ref.neverSent() || ref.differsFrom(title, block.getStartAt(), block.getEndAt())) {
                enqueue(userId, connection, ref, title, block, calendarId,
                        ref.neverSent() ? OutboundOperation.CREATE : OutboundOperation.UPDATE, now);
            }
        }

        // ③ 새로 생긴 자리
        //    🔴 새 UID 를 발급하기 전에 **다른 주에서 자리를 잃은 같은 태스크의 매핑**을 먼저 데려온다.
        //    주차 이동(PLAN-20)은 블록의 weekly_plan_id 만 바꾸고 매핑은 따라가지 않는다. 데려오지
        //    않으면 외부에 같은 태스크가 하나 더 생기고, 원래 주의 이벤트는 그 주를 다시 확정하기
        //    전까지 고아로 남는다(#82 리뷰 Blocking). 이동 경로(수동·자동 배치·재계획)마다 훅을 거는
        //    대신 확정 시점에 맞춘다 — 이 클래스가 블록 단위 훅을 쓰지 않는 것과 같은 이유다.
        Map<UUID, List<PlanBlockExternalRef>> strays = new HashMap<>();
        List<String> newKeys = wanted.keySet().stream().filter(k -> !storedKeys.contains(k)).sorted().toList();
        for (String key : newKeys) {
            PlanBlock block = wanted.get(key);
            int sequence = Integer.parseInt(key.substring(key.indexOf('#') + 1));
            List<PlanBlockExternalRef> candidates = strays.computeIfAbsent(block.getTaskId(),
                    taskId -> straysOf(userId, weeklyPlanId, taskId, connection));
            PlanBlockExternalRef ref;
            OutboundOperation operation;
            if (!candidates.isEmpty()) {
                ref = candidates.removeFirst();
                ref.relocate(weeklyPlanId, sequence, now);
                operation = ref.neverSent() ? OutboundOperation.CREATE : OutboundOperation.UPDATE;
            } else {
                ref = refRepository.save(PlanBlockExternalRef.reserve(
                        userId, connection.getId(), weeklyPlanId, block.getTaskId(), sequence, now));
                operation = OutboundOperation.CREATE;
            }
            enqueue(userId, connection, ref, titleOf(block.getTaskId()), block, calendarId, operation, now);
        }
    }

    /**
     * 이 태스크의 다른 주 매핑 중 <b>자기 주에서 자리가 사라진 것</b> — 그 주의 이 태스크 블록 수가
     * 매핑의 순번 이하로 줄었다. 블록이 다른 주로 옮겨 가면 원래 주에 이런 매핑이 남는다.
     * 같은 연동의 것만 데려온다.
     */
    private List<PlanBlockExternalRef> straysOf(UUID userId, UUID weeklyPlanId, UUID taskId,
                                                ExternalCalendarConnection connection) {
        Map<UUID, Long> countByPlan = new HashMap<>();
        List<PlanBlockExternalRef> strays = new ArrayList<>();
        for (PlanBlockExternalRef ref : refRepository.findByUserIdAndTaskId(userId, taskId)) {
            if (ref.getWeeklyPlanId().equals(weeklyPlanId) || !connection.getId().equals(ref.getConnectionId())) {
                continue;
            }
            long remaining = countByPlan.computeIfAbsent(ref.getWeeklyPlanId(),
                    planId -> planBlockRepository.findByWeeklyPlanId(planId).stream()
                            .filter(b -> b.getBlockType() == PlanBlockType.TASK && taskId.equals(b.getTaskId()))
                            .count());
            if (ref.getSequence() >= remaining) {
                strays.add(ref);
            }
        }
        strays.sort(Comparator.comparing(PlanBlockExternalRef::getSequence).thenComparing(PlanBlockExternalRef::getId));
        return strays;
    }

    /**
     * 태스크 제목 — 가리지 않고 그대로 내보낸다(계획 D6).
     *
     * <p>{@code plan_blocks} 에 title 컬럼이 없다(«불일치 원천 제거» — data-model §2.2). 그래서
     * 조인 대신 한 번 더 읽는다.
     */
    private String titleOf(UUID taskId) {
        List<String> found = jdbc.queryForList("SELECT title FROM tasks WHERE task_id = ?", String.class, taskId);
        return found.isEmpty() ? "" : found.getFirst();
    }

    /** 대상마다 안 나간 작업은 하나만 둔다 — 있으면 새로 쌓지 않고 내용을 고친다(#80 리뷰). */
    private void enqueue(UUID userId, ExternalCalendarConnection connection, PlanBlockExternalRef ref,
                         String title, PlanBlock block, String calendarId,
                         OutboundOperation operation, Instant now) {
        OutboundPayload payload = new OutboundPayload(ref.getExternalUid(), title,
                block.getStartAt(), block.getEndAt(),
                OutboundPayload.targetCalendar(ref.getSentCalendarId(), calendarId),
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());
        List<OutboundCalendarOp> unsent = opRepository.findUnsentByTarget(OutboundTargetType.PLAN_BLOCK, ref.getId());
        if (!unsent.isEmpty()) {
            unsent.get(unsent.size() - 1).replacePayload(payload, now);
            return;
        }
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.PLAN_BLOCK, ref.getId(), operation, payload, now));
    }

    /** 🔴 나간 적 없으면 대기 CREATE 를 거두고 DELETE 를 쌓지 않는다 — 유령·영구 실패를 막는다(#80 리뷰). */
    private void enqueueDelete(UUID userId, ExternalCalendarConnection connection, PlanBlockExternalRef ref,
                               String calendarId, Instant now) {
        opRepository.deleteAll(opRepository.findUnsentByTarget(OutboundTargetType.PLAN_BLOCK, ref.getId()));
        if (ref.neverSent()) {
            return;
        }
        OutboundPayload payload = new OutboundPayload(ref.getExternalUid(), null, null, null,
                OutboundPayload.targetCalendar(ref.getSentCalendarId(), calendarId),
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.PLAN_BLOCK, ref.getId(), OutboundOperation.DELETE, payload, now));
    }
}
