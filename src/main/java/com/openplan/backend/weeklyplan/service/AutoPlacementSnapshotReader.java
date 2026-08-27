package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.AutoPlacementRequest;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AutoPlacementService}의 DB 읽기 전담(스냅샷+후보 조립). 읽기 전용 tx는 <b>여기서 끝난다</b> —
 * {@link com.openplan.backend.rule.port.PlanPlacementPort#propose}(AI 배선이면 최대
 * {@code AiProperties.timeout}(기본 20초)의 동기 HTTP)는 이 메서드가 반환한 뒤, tx 밖에서 호출자가 부른다.
 *
 * <p><b>왜 별도 빈으로 뺐는가.</b> 원래는 {@code AutoPlacementService.propose()} 하나가
 * {@code @Transactional(readOnly = true)}로 이 읽기와 포트 호출을 통째로 감쌌다(PR #39 리뷰 지적) —
 * AI_BASE_URL이 설정된 배포에서 JDBC 커넥션을 쥔 채 최대 20초 대기하면, 동시 요청 수만큼 HikariCP 풀이
 * 그 대기에 묶여 이 기능과 무관한 요청까지 커넥션 획득 타임아웃으로 실패할 수 있었다. 같은 클래스 안에서
 * private 메서드에 {@code @Transactional}을 붙여도 자기 호출(self-invocation)은 프록시를 안 거쳐
 * 무시되므로(트랜잭션이 안 걸린다), 읽기 tx가 필요한 부분을 이 빈으로 분리해 진짜 빈 경계 호출로
 * 프록시를 타게 했다.
 *
 * <p><b>메서드는 반드시 public 이어야 한다.</b> Spring 의 프록시 기반 {@code @Transactional}(기본 모드)은
 * public 메서드 호출만 가로챈다 — package-private/protected 에 애노테이션을 달아도 조용히 무시된다
 * (예외 없이 그냥 tx 가 안 걸린다). 그래서 {@link #read}를 public 으로 둔다.
 */
@Component
public class AutoPlacementSnapshotReader {

    private final WeeklyPlanRepository weeklyPlanRepository;
    private final PlanBlockRepository planBlockRepository;
    private final TaskRepository taskRepository;
    private final PlanSnapshotAssembler assembler;

    public AutoPlacementSnapshotReader(WeeklyPlanRepository weeklyPlanRepository,
                                       PlanBlockRepository planBlockRepository,
                                       TaskRepository taskRepository,
                                       PlanSnapshotAssembler assembler) {
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.taskRepository = taskRepository;
        this.assembler = assembler;
    }

    /**
     * 스냅샷+후보 조립. 후보 = 요청 taskIds(미지정 시 미배치 전량, 사용자 미배치 집합으로 스코프).
     * 계획 부재·타인 → 404. 읽기 tx(이 메서드 반환과 함께 끝난다 — placementPort 호출 전).
     */
    @Transactional(readOnly = true)
    public CandidateSnapshot read(UUID userId, UUID planId, AutoPlacementRequest request) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        List<UUID> candidates = resolveCandidates(userId, request);

        List<BlockView> blocks = planBlockRepository.findByWeeklyPlanId(planId).stream()
                .map(this::toBlockView).toList();

        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blocks, Map.of(), candidates);
        return new CandidateSnapshot(snapshot, candidates);
    }

    /**
     * 후보 선정 — 사용자 미배치 태스크 전량을 기준으로, 요청에 taskIds가 있으면 그 교집합만(소유·미배치 스코프 강제).
     * 요청 id가 남의 것/이미 배치된 것이면 자연히 빠진다(존재 은닉·관대 처리).
     */
    private List<UUID> resolveCandidates(UUID userId, AutoPlacementRequest request) {
        List<UUID> unassigned = taskRepository.findUnassignedTaskIds(userId);
        if (request == null || request.taskIds() == null || request.taskIds().isEmpty()) {
            return unassigned;
        }
        Set<UUID> requested = new LinkedHashSet<>(request.taskIds());
        return unassigned.stream().filter(requested::contains).toList();
    }

    private BlockView toBlockView(PlanBlock b) {
        return new BlockView(b.getId(), BlockType.valueOf(b.getBlockType().name()),
                b.getTaskId(), b.getScheduleId(), b.getStartAt(), b.getEndAt());
    }

    /** 엔진 호출 재료 한 벌 — snapshot과 그 안에 태스크 사실이 채워진 candidates(taskIds). */
    public record CandidateSnapshot(PlanSnapshot snapshot, List<UUID> candidates) {
    }
}
