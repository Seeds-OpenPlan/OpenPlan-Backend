package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.availability.repository.AvailabilityPatternRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.port.PlanPlacementPort;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.repository.WbsItemRepository;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.AutoPlacementRequest;
import com.openplan.backend.weeklyplan.dto.PlacementProposalResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR #39 리뷰 Blocking 회귀 테스트 — {@code AutoPlacementService.propose()}가 읽기 DB 트랜잭션을 쥔 채
 * {@link PlanPlacementPort#propose}(AI 배선이면 동기 HTTP, 최대 {@code AiProperties.timeout})를 부르지
 * 않는지를 고정한다. DB 없이도(Docker 불요) 실제 tx 경계를 관찰하려고, {@code @Transactional} 애노테이션을
 * 처리하는 진짜 Spring AOP({@link TransactionInterceptor})를 커밋/롤백이 빈 동작인 가짜
 * {@link org.springframework.transaction.PlatformTransactionManager} 위에서 그대로 돌린다 —
 * {@link AbstractPlatformTransactionManager}가 실커넥션과 무관하게
 * {@link TransactionSynchronizationManager#isActualTransactionActive()} 플래그를 관리해 주므로, "읽기가
 * 끝나면 tx도 끝났는가"를 그대로 관찰할 수 있다.
 *
 * <p>이 테스트를 되돌려 {@code AutoPlacementSnapshotReader.read()} 안에서 (또는 그 상위인
 * {@code AutoPlacementService.propose()} 전체를) {@code @Transactional}로 다시 감싸면
 * {@code txActiveDuringPortCall}이 {@code true}로 관측되어 실패한다 — 직접 확인함(아래 검증 결과 참고).
 */
class AutoPlacementTransactionBoundaryTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();

    @Test
    void placementPort_호출_시점엔_읽기_트랜잭션이_이미_끝나_있다() {
        WeeklyPlanRepository weeklyPlanRepository = mock(WeeklyPlanRepository.class);
        PlanBlockRepository planBlockRepository = mock(PlanBlockRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        WbsItemRepository wbsItemRepository = mock(WbsItemRepository.class);
        AvailabilityPatternRepository availabilityRepository = mock(AvailabilityPatternRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserClock clock = mock(UserClock.class);

        WeeklyPlan plan = new WeeklyPlan(USER_ID, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9), Instant.now());
        when(weeklyPlanRepository.findByIdAndUserId(PLAN_ID, USER_ID)).thenReturn(Optional.of(plan));
        when(planBlockRepository.findByWeeklyPlanId(PLAN_ID)).thenReturn(List.of());
        when(taskRepository.findUnassignedTaskIds(USER_ID)).thenReturn(List.of());
        when(availabilityRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(clock.zoneOf(USER_ID)).thenReturn(ZoneOffset.UTC);
        when(clock.now()).thenReturn(Instant.now());
        // PlanSnapshotAssembler.activeFixedWindows() 가 고정일정 baseline 테이블을 JdbcTemplate 로 직접 읽는다.
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

        PlanSnapshotAssembler assembler =
                new PlanSnapshotAssembler(availabilityRepository, taskRepository, wbsItemRepository, jdbc, clock);
        AutoPlacementSnapshotReader reader =
                new AutoPlacementSnapshotReader(weeklyPlanRepository, planBlockRepository, taskRepository, assembler);
        AutoPlacementSnapshotReader proxiedReader = transactionalProxy(reader);

        // AI 어댑터를 흉내낸다 — propose() 호출 "그 순간"의 tx 활성 여부를 기록한다.
        // 초깃값 true 로 둬서, 아래 람다가 아예 안 불리는 버그(예: 예외로 조기 종료)가 나면 오검출 없이 실패하게 한다.
        boolean[] txActiveDuringPortCall = {true};
        PlanPlacementPort placementPort = (snapshot, taskIds) -> {
            txActiveDuringPortCall[0] = TransactionSynchronizationManager.isActualTransactionActive();
            return new PlacementResult(List.of(), List.of());
        };

        AutoPlacementService service = new AutoPlacementService(proxiedReader, placementPort);
        PlacementProposalResponse response = service.propose(USER_ID, PLAN_ID, new AutoPlacementRequest(null));

        assertThat(response).isNotNull();
        assertThat(txActiveDuringPortCall[0])
                .as("PlanPlacementPort.propose() 호출 시점엔 읽기 트랜잭션이 이미 닫혀 있어야 한다 — "
                        + "DB 커넥션을 쥔 채 AI 동기 호출(최대 20초)을 기다리면 동시 요청 수만큼 커넥션 풀이 "
                        + "고갈될 수 있다 (PR #39 리뷰 지적)")
                .isFalse();
    }

    /**
     * {@code @Transactional(readOnly = true)}가 실제로 걸리도록, 진짜 Spring tx AOP를 no-op
     * 트랜잭션 매니저 위에 씌운다. {@code proxyTargetClass=true}로 CGLIB 서브클래스 프록시를 만든다
     * ({@link AutoPlacementSnapshotReader}는 인터페이스가 없다).
     */
    private static AutoPlacementSnapshotReader transactionalProxy(AutoPlacementSnapshotReader target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new NoOpTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());

        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (AutoPlacementSnapshotReader) factory.getProxy();
    }

    /** 실제 커넥션 없이 tx active 플래그만 관리하는 가짜 매니저 — begin/commit/rollback은 전부 no-op. */
    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op — 진짜 리소스가 없다. active 플래그 관리는 AbstractPlatformTransactionManager가 한다.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op
        }
    }
}
