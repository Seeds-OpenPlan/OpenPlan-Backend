package com.openplan.backend.dashboard.dto;

import java.util.List;

/** DASH-05/RB-DASH-02 오늘 실행 보드. */
public record TodayBoardResponse(List<TodayBoardItemResponse> items, int remainingMinutes) {
}
