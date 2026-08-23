package com.openplan.backend.structuring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 태스크 구조화 초안 (SS-03 / RB-PROJ-01) — V1 baseline {@code task_structuring_drafts} 매핑.
 *
 * <p><b>초안이지 태스크가 아니다</b>(C-2). 사용자가 수정한 뒤 {@code POST /projects/{id}/tasks/bulk}
 * 로 채택해야 비로소 태스크가 된다. 채택되면 {@code isAdopted} 가 켜진다 — 같은 초안을 두 번
 * 채택하는 것을 막기 위해서가 아니라, "제안 중 무엇이 쓰였나" 를 남기기 위해서다.
 *
 * <p><b>생성은 정적 규칙 사전이 한다 — LLM 이 아니다</b>(C-1). 그래서 같은 프로젝트명이면 항상 같은
 * 초안이 나온다. {@code reason} 은 매칭된 사전 항목을 그대로 옮긴 것이라 사용자에게 근거가 된다(C-3).
 */
@Getter
@Entity
@Table(name = "task_structuring_drafts")
public class TaskStructuringDraft {

    @Id
    @Column(name = "task_structuring_draft_id")
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false, length = 200)
    private String title;

    /** 5분 단위·양수 (ck_draft_estimated). 사전 값이 그 규약을 이미 지킨다. */
    @Column(name = "proposed_estimated_minutes")
    private Integer proposedEstimatedMinutes;

    @Column(name = "proposed_priority")
    private Integer proposedPriority;

    @Column(name = "is_adopted", nullable = false)
    private boolean adopted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskStructuringDraft() {
    }

    public TaskStructuringDraft(UUID projectId, String title, Integer estimatedMinutes,
                                Integer priority, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.title = title;
        this.proposedEstimatedMinutes = estimatedMinutes;
        this.proposedPriority = priority;
        this.adopted = false;
        this.createdAt = createdAt;
    }

    /** 채택 표시 (tasks/bulk 에서 draftId 로 지목됐을 때). 이미 채택된 것을 다시 눌러도 무해하다. */
    public void markAdopted() {
        this.adopted = true;
    }
}
