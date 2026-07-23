package com.openplan.backend.task.repository;

import com.openplan.backend.task.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 태스크 저장소 (ST-B2-03). 소유는 tasks→projects.user_id 조인으로 판정한다(tasks에 user_id 없음 — D-16).
 *
 * <p>본 슬라이스(생성)는 {@code save}만 사용한다. 조회 계열 쿼리(소유 체인 단건·목록·미배치)는
 * 후속 슬라이스에서 code-structure §4 설계대로 추가한다.
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {
}
