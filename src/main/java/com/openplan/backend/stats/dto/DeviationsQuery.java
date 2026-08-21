package com.openplan.backend.stats.dto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** {@code GET /stats/deviations} 쿼리 (openapi: period·groupBy 필수, baseDate 선택). {@link StatsSummaryQuery} 참고. */
public class DeviationsQuery {

    @NotBlank(message = "period는 필수입니다.")
    private String period;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate baseDate;

    @NotBlank(message = "groupBy는 필수입니다.")
    private String groupBy;

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public LocalDate getBaseDate() {
        return baseDate;
    }

    public void setBaseDate(LocalDate baseDate) {
        this.baseDate = baseDate;
    }

    public String getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(String groupBy) {
        this.groupBy = groupBy;
    }
}
