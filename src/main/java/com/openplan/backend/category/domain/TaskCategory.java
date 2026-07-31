package com.openplan.backend.category.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 태스크 카테고리 프리셋 (ST-B2-04 / SC-01 · FR-303~305). V1 baseline {@code task_categories} 매핑 —
 * 스키마 델타 0. ddl-auto=validate이므로 컬럼·타입이 스키마와 정확히 일치해야 한다.
 *
 * <p><b>사용자별 프리셋</b>: 사용자가 만들어 두고 태스크에 붙이는 카테고리 목록. {@code (user_id, name)} UNIQUE로
 * 사용자 내 이름 중복을 DB가 막는다. 삭제 시 연결된 {@code tasks.category_id}는 FK ON DELETE SET NULL로
 * 자동 '없음'(null) 전환된다(FR-305 — 앱 코드 별도 갱신 불요).
 *
 * <p><b>version 없음</b>(스키마 사실) · createdAt은 서비스가 {@code UserClock.now()}로 주입한다(P-2 승계).
 */
@Entity
@Table(name = "task_categories")
public class TaskCategory {

    @Id
    @Column(name = "task_category_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected TaskCategory() {
    }

    /**
     * 생성 규정 (SC-01). id는 앱 측 확정(flush 전 응답 조립). createdAt은 UserClock 주입값(P-2).
     * sortOrder는 재정렬 엔드포인트가 없어 기본 0(목록 정렬은 sort_order ASC, name ASC — 사실상 name ASC).
     *
     * @param name 이미 trim·검증된 이름(서비스가 TaskCategoryValidator를 통과시킨 값)
     */
    public TaskCategory(UUID userId, String name, int sortOrder, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.sortOrder = sortOrder;
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

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
