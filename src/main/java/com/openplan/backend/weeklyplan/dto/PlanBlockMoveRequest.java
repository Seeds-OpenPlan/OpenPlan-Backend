package com.openplan.backend.weeklyplan.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 블록 이동·시간 조정 요청 (PLAN-19·20 / 정본 openapi.yaml {@code movePlanBlock}).
 *
 * <p><b>부분 수정(PATCH)</b> — 담겨 온 필드만 반영한다(태스크·고정일정 편집과 동일 컨벤션).
 * {@code startAt}/{@code endAt}은 시각 조정(PLAN-19), {@code targetWeekStartDate}는 주차 이동(PLAN-20 —
 * 대상 주차 초안 get-or-create). 셋 다 선택이며, 주차 이동 시엔 그 주에 맞는 {@code startAt}/{@code endAt}을
 * 함께 보내는 것이 정상 흐름이다(FE가 대상 주 날짜로 재계산).
 */
@Getter
public class PlanBlockMoveRequest {

    private Instant startAt;
    private Instant endAt;
    private LocalDate targetWeekStartDate;

    /** JSON에 담겨 온 필드 이름 집합 — setter 호출로 채워진다. */
    @JsonIgnore
    private final Set<String> provided = new HashSet<>();

    /** 해당 필드가 요청에 담겨 왔는가(값이 null이어도 true). 미포함이면 false — 기존 값 유지. */
    public boolean isProvided(String field) {
        return provided.contains(field);
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
        provided.add("startAt");
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
        provided.add("endAt");
    }

    public void setTargetWeekStartDate(LocalDate targetWeekStartDate) {
        this.targetWeekStartDate = targetWeekStartDate;
        provided.add("targetWeekStartDate");
    }
}
