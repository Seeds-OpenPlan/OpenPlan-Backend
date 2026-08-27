package com.openplan.backend.schedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.util.HashSet;
import java.util.Set;

/**
 * 일정 편집 요청 (PLAN-17 / 정본 openapi.yaml {@code updateSchedule}).
 *
 * <p><b>부분 수정(PATCH)</b> — 담겨 온 필드만 반영한다(태스크·고정일정 편집과 동일 컨벤션). 담겨 왔지만 값이
 * null이면 "해제"로 본다(estimatedMinutes·priority·memo는 null 허용). {@code version}은 낙관락 입력이라 필수(누락 → 400).
 * 값 규칙(제목·5분 단위·우선순위)은 {@code ScheduleValidator}가 422로 판정한다. 시각·상태는 편집 대상이 아니다.
 */
@Getter
public class ScheduleUpdateRequest {

    private String title;
    private Integer estimatedMinutes;
    private Integer priority;
    private String memo;

    @NotNull(message = "version은 필수입니다.")
    private Long version;

    /** JSON에 담겨 온 필드 이름 집합 — setter 호출로 채워진다. */
    @JsonIgnore
    private final Set<String> provided = new HashSet<>();

    /** 해당 필드가 요청에 담겨 왔는가(값이 null이어도 true — "해제"). 미포함이면 false — 기존 값 유지. */
    public boolean isProvided(String field) {
        return provided.contains(field);
    }

    public void setTitle(String title) {
        this.title = title;
        provided.add("title");
    }

    public void setEstimatedMinutes(Integer estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
        provided.add("estimatedMinutes");
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
        provided.add("priority");
    }

    public void setMemo(String memo) {
        this.memo = memo;
        provided.add("memo");
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
