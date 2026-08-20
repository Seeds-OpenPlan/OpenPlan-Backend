package com.openplan.backend.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * WBS 기간 애그리거트 (ST-B2-05). V1 baseline {@code wbs_items} 테이블 매핑 — 스키마 델타 0.
 * ddl-auto=validate이므로 컬럼·타입이 스키마와 정확히 일치해야 한다({@link Task} 관례 승계).
 *
 * <p><b>task와 1:0..1</b>(스키마 {@code task_id UNIQUE}) — 태스크당 WBS 기간은 최대 하나다.
 * 소유는 이 엔티티가 갖지 않는다: {@code wbs_items}에 {@code user_id}가 없어(tasks와 동일하게
 * D-16 승계) 소유 판정은 항상 {@code tasks → projects.user_id} 체인으로 먼저 끝낸 뒤 이 테이블을
 * 건드린다(TaskService.saveWbsRange 참고).
 *
 * <p><b>낙관락 컬럼 없음</b>: 정본 openapi.yaml의 {@code saveWbsRange} 요청 바디에도 version이
 * 없고 스키마에도 {@code version} 컬럼이 없다 — 이 리소스는 "설정"(업서트) 의미라 last-write-wins로
 * 설계됐다(다른 편집 엔드포인트의 필수 version 관례와 의도적으로 다르다).
 *
 * <p>쓰기는 이 엔티티의 setter/save가 아니라 {@link com.openplan.backend.task.repository.WbsItemRepository#upsert}
 * 원자적 SQL로 이뤄진다 — 필드는 응답 조립을 위한 읽기 전용 매핑이다.
 */
@Entity
@Table(name = "wbs_items")
public class WbsItem {

    @Id
    @Column(name = "wbs_item_id")
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected WbsItem() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
