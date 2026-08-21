package com.openplan.backend.stats.dto;

import java.util.List;

/** {@code DeviationReport} 응답 (openapi 1:1). */
public record DeviationReportResponse(String groupBy, boolean empty, List<DeviationRowResponse> rows) {
}
