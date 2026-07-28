package com.openplan.backend.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import lombok.Getter;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
public class TaskUpdateRequest {

    private String title;
    private String memo;
    private Integer estimatedMinutes;
    private Integer priority;
    private LocalDate dueDate;
    private UUID categoryId;

    @NotNull(message = "version은 필수입니다.")
    private Long version;

    @Null(message = "status는 지정할 수 없습니다.")
    private String status;

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

    public void setMemo(String memo) {
        this.memo = memo;
        provided.add("memo");
    }

    public void setEstimatedMinutes(Integer estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
        provided.add("estimatedMinutes");
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
        provided.add("priority");
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
        provided.add("dueDate");
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
        provided.add("categoryId");
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
