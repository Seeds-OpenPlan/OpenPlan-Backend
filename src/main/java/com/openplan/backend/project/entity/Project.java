package com.openplan.backend.project.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 프로젝트 애그리거트 루트 (ST-B2-01). V1 baseline {@code projects} 테이블 매핑 —
 * 스키마 델타 0(ADR-B2-01-005). ddl-auto=validate이므로 컬럼·타입이 스키마와 정확히 일치해야 한다.
 *
 * <p><b>불변식</b>: {@code status = CLOSED ⇔ closed_at IS NOT NULL} — 상태 전이 메서드에서만 유지한다.
 * setter를 두지 않아 우회 변경을 구조적으로 차단한다(전이·편집 메서드는 각 슬라이스에서 추가).
 *
 * <p><b>시각 소스(P-2)</b>: createdAt·closedAt·평가 now는 전부 서비스가 {@code UserClock}로 주입한다 —
 * 엔티티는 {@code Instant.now()}를 직접 호출하지 않는다(테스트 결정성, C4).
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @Column(name = "project_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    private Integer priority;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected Project() {
    }

    /**
     * 생성 규정 (PROJ-02 / AC-02-1): status=IN_PROGRESS, closedAt=null, version=0.
     * id·createdAt는 앱 측에서 확정한다(flush 전 응답 조립·로그를 위해). createdAt는 UserClock 주입값(P-2).
     *
     * @param name 이미 trim·검증된 이름(서비스가 ProjectValidator를 통과시킨 값)
     */
    public Project(UUID userId, String name, String description, LocalDate dueDate,
                   Integer priority, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.dueDate = dueDate;
        this.priority = priority;
        this.status = ProjectStatus.IN_PROGRESS;
        this.closedAt = null;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public Integer getPriority() {
        return priority;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
