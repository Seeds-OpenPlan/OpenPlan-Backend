package com.openplan.backend.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 오늘 실행 보드 1행.
 *
 * <p>{@code selectionRank}는 <b>항상 null</b>이다 — SS-02(service-stories.md)가 정렬 키(우선순위→남은
 * 마감일→완료/미완료)까지는 확정했지만 "타이브레이크 결정적 정의 필요"라고 명시한 채 후속 확정문이 없다
 * (stats-dashboard-notes.md §1.2). 목록 자체는 {@code startAt} 오름차순으로만 정렬했다 — 이건 서술적
 * 정렬일 뿐 SS-02가 요구하는 "규칙 선정 순위"가 아니다.
 */
public record TodayBoardItemResponse(
        UUID planBlockId, UUID taskId, String title,
        Instant startAt, Instant endAt, Integer estimatedMinutes,
        boolean completed, Integer selectionRank) {
}
