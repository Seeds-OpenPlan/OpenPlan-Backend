package com.openplan.backend.dashboard.dto;

import java.util.List;

/** {@code DashboardView} 응답 (openapi 1:1, ST-B2-15). */
public record DashboardResponse(
        StatusBoardResponse statusBoard,
        PriorityActionResponse priorityAction,
        List<RiskIssueResponse> riskIssues,
        TodayBoardResponse todayBoard,
        List<ImpactedProjectResponse> weeklyImpactProjects,
        List<BusyWeekdayResponse> busyWeekdays) {
}
