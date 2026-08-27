package com.openplan.backend.task.repository;

import com.openplan.backend.task.domain.WbsItem;

/**
 * WBS 뷰 결과 프로젝션 (code-structure §4) — WBS 행 + 소속 태스크 제목을 한 쿼리로 함께 획득한다.
 * 정본 {@code WbsItem} 응답 shape에 taskTitle이 있는데 WbsItem 엔티티에는 Task 연관이 없으므로
 * ({@link WbsItem} 상단 참고 — 소유 체인은 JPQL 명시 조인), 목록 조회에서 N+1 없이 채우는 read-model.
 */
public record WbsItemRow(WbsItem item, String taskTitle) {
}
