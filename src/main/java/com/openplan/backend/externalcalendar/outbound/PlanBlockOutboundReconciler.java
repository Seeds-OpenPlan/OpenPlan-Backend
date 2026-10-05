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
        for (var entry : wanted.entrySet()) {
            if (storedKeys.contains(entry.getKey())) {
                continue;
            }
            PlanBlock block = entry.getValue();
            int sequence = Integer.parseInt(entry.getKey().substring(entry.getKey().indexOf('#') + 1));
            PlanBlockExternalRef ref = refRepository.save(PlanBlockExternalRef.reserve(
                    userId, connection.getId(), weeklyPlanId, block.getTaskId(), sequence, now));
            enqueue(userId, connection, ref, titleOf(block.getTaskId()), block, calendarId,
                    OutboundOperation.CREATE, now);
        }
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

    private void enqueue(UUID userId, ExternalCalendarConnection connection, PlanBlockExternalRef ref,
                         String title, PlanBlock block, String calendarId,
                         OutboundOperation operation, Instant now) {
        OutboundPayload payload = new OutboundPayload(ref.getExternalUid(), title,
                block.getStartAt(), block.getEndAt(), calendarId,
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.PLAN_BLOCK, ref.getId(), operation, payload, now));
    }

    private void enqueueDelete(UUID userId, ExternalCalendarConnection connection, PlanBlockExternalRef ref,
                               String calendarId, Instant now) {
        OutboundPayload payload = new OutboundPayload(ref.getExternalUid(), null, null, null, calendarId,
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.PLAN_BLOCK, ref.getId(), OutboundOperation.DELETE, payload, now));
    }
}
