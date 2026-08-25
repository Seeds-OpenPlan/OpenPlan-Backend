package com.openplan.backend.executionlog.repository;

import com.openplan.backend.executionlog.domain.ExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 수행 이력 저장소 (PLAN-15). 기록은 추가만 한다(편집·삭제 계약 없음 — 명세에 해당 오퍼레이션 부재).
 *
 * <p>W5 수행 통계(요약·편차·시간 패턴)는 {@code ix_exec_logs_user_started}(user_id, started_at)로
 * 온더플라이 집계한다(B9) — {@link #findByUserIdAndStartedAtRange}가 그 진입점이다(ST-B2-16).
 */
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {

    /**
     * 기간 집계 진입점. {@code [from, to)} 반개구간 — to는 호출 측이 "기간 끝 다음날 자정"으로 넘긴다
     * (사용자 timezone 자정 경계를 이 레이어가 재해석하지 않도록 Instant 경계는 서비스가 확정해서 넘김).
     */
    List<ExecutionLog> findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            UUID userId, Instant from, Instant to);

    /**
     * 기간 미한정 전체 이력 — DASH-06 ACTUAL_OVERRUN 배지(프로젝트별 실제 vs 예상, 전체 이력 기준 —
     * dashboard 라우트에 기간 파라미터가 없어 채택, stats-dashboard-notes.md 참고) 전용.
     * MVP 규모(사용자당 이력 수 적음) 전제 — 이력이 커지면 배지 판정도 기간·페이지네이션이 필요해진다.
     */
    List<ExecutionLog> findByUserId(UUID userId);
}
