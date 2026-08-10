package com.openplan.backend.weeklyplan.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 주간 계획 조회 쿼리 (ST-B2-07 / PLAN-02). {@code @ModelAttribute}로 바인딩된다 — raw @RequestParam은 누락·형식
 * 오류가 500으로 새므로 쓰지 않는다(@ModelAttribute는 바인딩 오류를 MethodArgumentNotValidException → 400으로 라우팅).
 */
public class WeeklyPlanQuery {

    @NotNull(message = "weekStartDate는 필수입니다.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate weekStartDate;

    public LocalDate getWeekStartDate() {
        return weekStartDate;
    }

    public void setWeekStartDate(LocalDate weekStartDate) {
        this.weekStartDate = weekStartDate;
    }
}
