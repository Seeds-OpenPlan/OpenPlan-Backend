package com.openplan.backend.executionlog.repository;

import com.openplan.backend.executionlog.domain.ExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 수행 이력 저장소 (PLAN-15). 기록은 추가만 한다(편집·삭제 계약 없음 — 명세에 해당 오퍼레이션 부재).
 *
 * <p>W5 수행 통계는 {@code ix_exec_logs_user_started}(user_id, started_at)로 온더플라이 집계한다(B9).
 * 집계 쿼리는 통계 슬라이스에서 추가한다 — 여기서 선행 정의하지 않는다.
 */
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {
}
