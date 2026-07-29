package com.openplan.backend.task.repository;

import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.task.domain.Task;

/**
 * 소유 체인 단건 조회 결과 (code-structure §4) — 태스크와 그 소속 프로젝트의 status를 한 쿼리로 함께 획득한다.
 *
 * <p>tasks에 user_id가 없어(D-16) 소유 판정은 {@code tasks → projects.user_id} JPQL 조인으로만 가능하다.
 * projectStatus는 D-10 CLOSED 가드(편집·status·삭제)의 입력이며, 단건 조회(EP-3)는 task만 사용한다.
 */
public record OwnedTask(Task task, ProjectStatus projectStatus) {
}
