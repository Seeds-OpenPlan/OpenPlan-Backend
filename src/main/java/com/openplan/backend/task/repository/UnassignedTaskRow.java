package com.openplan.backend.task.repository;

import com.openplan.backend.task.domain.Task;

/**
 * 미배치 조회 결과 프로젝션 (code-structure §4) — 태스크 + 소속 프로젝트명을 한 쿼리로 함께 획득한다(D-5a).
 * 미배치 패널·배치 피커가 프로젝트명을 함께 표시하므로 조인 파생으로 담는다.
 */
public record UnassignedTaskRow(Task task, String projectName) {
}
