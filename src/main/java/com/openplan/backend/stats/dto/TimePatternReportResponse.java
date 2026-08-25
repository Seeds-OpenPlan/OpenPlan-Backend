package com.openplan.backend.stats.dto;

import java.util.List;

/** {@code TimePatternReport} 응답 (openapi 1:1). */
public record TimePatternReportResponse(boolean empty, List<TimeSlotResponse> slots) {
}
