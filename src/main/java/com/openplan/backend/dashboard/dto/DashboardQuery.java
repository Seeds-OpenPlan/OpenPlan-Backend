package com.openplan.backend.dashboard.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** {@code GET /dashboard} 쿼리 (openapi: baseDate 선택, "미지정 시 오늘(사용자 timezone)"). */
public class DashboardQuery {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate baseDate;

    public LocalDate getBaseDate() {
        return baseDate;
    }

    public void setBaseDate(LocalDate baseDate) {
        this.baseDate = baseDate;
    }
}
