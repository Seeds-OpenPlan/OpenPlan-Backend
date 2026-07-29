package com.openplan.backend.task.domain;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 태스크 애그리거트 (ST-B2-03). V1 baseline {@code tasks} 테이블 매핑 — 스키마 델타 0(data-model §2).
 * ddl-auto=validate이므로 컬럼·타입이 스키마와 정확히 일치해야 한다.
 *
 * <p><b>소유는 projects.user_id 체인으로만 판정</b>(D-16) — tasks에 user_id 비정규화 컬럼을 두지 않는다.
 * Project 연관 매핑도 만들지 않는다(소유 체인은 JPQL 명시 조인, code-structure §4).
 *
 * <p><b>상태 단일 진입점</b>: status를 바꾸는 지점은 아래 4메서드뿐이다(setter 부재 — Project 관례 승계).
 * TaskStatus에 canTransitionTo가 없는 이유는 {@link TaskStatus} 참고(ADR-B2-03-001).
 *
 * <p><b>시각 소스(P-2)</b>: createdAt은 서비스가 {@code UserClock.now()}로 주입한다 —
 * 엔티티는 {@code Instant.now()}를 직접 호출하지 않는다(테스트 결정성).
 */
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @Column(name = "task_id")
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String memo;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    private Integer priority;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected Task() {
    }

    /**
     * 생성 규정 (PROJ-17 / AC-C-1): status=UNASSIGNED, version=0. id·createdAt는 앱 측에서 확정한다
     * (flush 전 응답 조립을 위해). createdAt는 UserClock 주입값(P-2).
     *
     * @param trimmedTitle 이미 trim·검증된 제목(서비스가 TaskValidator를 통과시킨 값)
     */
    public Task(UUID projectId, UUID categoryId, String trimmedTitle, String memo,
                Integer estimatedMinutes, Integer priority, LocalDate dueDate, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.categoryId = categoryId;
        this.title = trimmedTitle;
        this.memo = memo;
        this.estimatedMinutes = estimatedMinutes;
        this.priority = priority;
        this.dueDate = dueDate;
        this.status = TaskStatus.UNASSIGNED;
        this.createdAt = createdAt;
    }

    /**
     * 편집 6필드 전체 교체 (PROJ-18 / PATCH). status는 건드리지 않는다 — 상태 변경은 전용 경로(D-3)만 사용.
     * categoryId=null = 카테고리 해제(AC-E-1). title은 서비스가 검증·trim한 값. version은 flush 시 자동 증가.
     */
    public void edit(String trimmedTitle, String memo, Integer estimatedMinutes,
                     Integer priority, LocalDate dueDate, UUID categoryId) {
        this.title = trimmedTitle;
        this.memo = memo;
        this.estimatedMinutes = estimatedMinutes;
        this.priority = priority;
        this.dueDate = dueDate;
        this.categoryId = categoryId;
    }

    /**
     * TT-3 완료 전환. IN_PROGRESS→COMPLETED. UNASSIGNED에서 호출되면 TT-6 불허 → 422 E-PROJ-003(D-1b).
     * 호출 전제: 동일 상태(이미 COMPLETED) no-op은 서비스가 이 호출 전에 단락한다(AC-S-5).
     */
    public void complete() {
        if (this.status == TaskStatus.UNASSIGNED) {                 // TT-6 (D-1b User 확정)
            throw new OpenPlanException(ErrorCode.E_PROJ_003,
                    Map.of("fields", List.of(Map.of("field", "status"))));
        }
        this.status = TaskStatus.COMPLETED;
    }

    /**
     * TT-4/TT-5 미완료 되돌리기. COMPLETED에서만 호출(no-op 단락 계약 동일).
     * 착지 = hasBlocks ? IN_PROGRESS : UNASSIGNED — 불변식 "IN_PROGRESS ⇔ 블록≥1" 유지(D-1c).
     *
     * @return 착지 상태(서비스가 미러 필요 여부 판정에 사용)
     */
    public TaskStatus reopen(boolean hasBlocks) {
        this.status = hasBlocks ? TaskStatus.IN_PROGRESS : TaskStatus.UNASSIGNED;
        return this.status;
    }

    /**
     * TT-1 인바운드 계약(ST-B2-08 전용 — 이번 스토리 엔드포인트 없음): UNASSIGNED→IN_PROGRESS.
     * IN_PROGRESS면 no-op. COMPLETED면 허용 여부 미결이라 잠정 방어(IllegalStateException).
     */
    public void onBlockAssigned() {
        if (this.status == TaskStatus.COMPLETED) {
            throw new IllegalStateException("COMPLETED 태스크 신규 블록 배치는 ST-B2-08 미결(잠정 방어)");
        }
        this.status = TaskStatus.IN_PROGRESS;                       // UNASSIGNED→IN_PROGRESS, IN_PROGRESS→동일값
    }

    /**
     * TT-2 인바운드 계약(ST-B2-08 전용): IN_PROGRESS→UNASSIGNED.
     * COMPLETED면 불변(SSM §5 규칙 2), UNASSIGNED면 no-op(멱등).
     */
    public void onLastBlockRemoved() {
        if (this.status == TaskStatus.IN_PROGRESS) {
            this.status = TaskStatus.UNASSIGNED;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getMemo() {
        return memo;
    }

    public Integer getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public Integer getPriority() {
        return priority;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
