package com.openplan.backend.stats.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * {@code GET /stats/time-patterns} 쿼리 (openapi: period·baseDate 둘 다 선택 — summaries/deviations와
 * 달리 required가 아니다). period 생략 시 기본값은 {@code StatsService}가 WEEKLY로 정한다
 * (정본에 기본값 명시 없음 — enum 첫 값 채택, 리스크 낮은 서식 결정이라 별도 확인 없이 적용. 리드가
 * 다르게 정하면 이 기본값만 교체하면 된다).
 */
public class TimePatternsQuery {

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
