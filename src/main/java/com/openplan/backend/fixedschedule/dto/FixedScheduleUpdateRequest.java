package com.openplan.backend.fixedschedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 고정 일정 편집 요청 (FIX-06 / 정본 openapi.yaml {@code FixedScheduleInput} + version).
 *
 * <p><b>true PATCH(부분 수정)</b> — 요청 JSON에 <b>담겨 온 필드만</b> 바꾸고, 안 담긴 필드는 기존 값을 유지한다
 * (HTTP PATCH 의미 · 태스크 편집과 동일 컨벤션). 담겨 왔지만 값이 null이면 "해제"로 본다(isProvided=true).
 * {@code version}은 낙관락 입력이라 필수(누락 → 400). 값 규칙은 {@code FixedScheduleValidator}가 422로 판정한다.
 * source·status는 서버 관리라 편집 대상이 아니다.
 */
@Getter
public class FixedScheduleUpdateRequest {

    private String title;
    private String weekday;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDate startDate;
    private LocalDate endDate;

    @NotNull(message = "version은 필수입니다.")
    private Long version;

    /** JSON에 담겨 온(=수정 대상) 필드 이름 집합. setter 호출로 채워진다. */
    @JsonIgnore
    private final Set<String> provided = new HashSet<>();

    /** 해당 필드가 요청 JSON에 담겨 왔는가(값이 null이어도 true — "해제"). 미포함이면 false — "유지". */
    public boolean isProvided(String field) {
        return provided.contains(field);
    }

    public void setTitle(String title) {
        this.title = title;
        provided.add("title");
    }

    public void setWeekday(String weekday) {
        this.weekday = weekday;
        provided.add("weekday");
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
        provided.add("startTime");
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
        provided.add("endTime");
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
        provided.add("startDate");
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        provided.add("endDate");
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
