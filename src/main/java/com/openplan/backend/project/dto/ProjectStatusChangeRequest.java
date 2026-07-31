package com.openplan.backend.project.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 프로젝트 상태 변경 요청 (PROJ-07 / EP-5). {@code dueDate}는 재개(→IN_PROGRESS) 시에만 의미가 있으며
 * <b>tri-state</b>로 다룬다(Q-C2): 필드 부재 = 기존 유지, {@code null} = 무기한 전환, 값 = 마감일 교체.
 *
 * <p>record가 아닌 class인 이유: record는 "필드 부재"와 "명시적 null"을 구분할 수 없다. 커스텀 setter가
 * 호출됐는지로 "제공됨(dueDateProvided)"을 판별한다(외부 의존 없이 tri-state 구현 — design-patterns §4).
 */
public class ProjectStatusChangeRequest {

    @NotBlank(message = "status는 필수입니다.")
    private String status;

    @NotNull(message = "version은 필수입니다.")
    private Long version;

    private LocalDate dueDate;
    private boolean dueDateProvided;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /** Jackson이 "dueDate" 키를 만나 이 setter를 호출하면 제공된 것으로 표시(null 포함). */
    @JsonProperty("dueDate")
    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
        this.dueDateProvided = true;
    }

    public boolean isDueDateProvided() {
        return dueDateProvided;
    }
}
