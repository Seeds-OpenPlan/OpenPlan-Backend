package com.openplan.backend.stats.dto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * {@code GET /stats/summaries} 쿼리 (openapi: period 필수, baseDate 선택). {@code @ModelAttribute}로
 * 바인딩한다 — raw {@code @RequestParam}은 누락·형식 오류가 500으로 새므로 쓰지 않는다(ST-B2-01 규범 승계,
 * {@code TaskListQuery} 선례). period는 String으로 받아 서비스에서 enum 매핑한다(미정의값 500 회피 —
 * {@code TaskListQuery.status} 선례와 동일하게 값 오류는 서비스가 422 E-COM-009로 분류).
 */
public class StatsSummaryQuery {

    @NotBlank(message = "period는 필수입니다.")
    private String period;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate baseDate;

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
}
