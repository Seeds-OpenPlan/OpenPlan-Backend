package com.openplan.backend.dashboard.dto;

import java.time.LocalDate;

/**
 * DASH-01 상태 보드. {@code deltaMinutes = availableMinutes - plannedMinutes}(양수=여유, 음수=초과).
 *
 * <p>us-decisions-kr.md §4.5의 과부하/여유 부족/무리 없음 3단 판정식은 이 응답에 <b>포함하지 않는다</b> —
 * openapi {@code DashboardView.statusBoard} 스키마에 그 필드가 없다(정본을 글자 그대로 따름). 3단 판정이
 * 필요하면 FE가 availableMinutes·plannedMinutes로 같은 식을 재계산하거나, 스키마에 필드를 추가하는 계약
 * 변경이 먼저 필요하다.
 */
public record StatusBoardResponse(
        LocalDate weekStartDate, int plannedMinutes, int availableMinutes, int deltaMinutes) {
}
