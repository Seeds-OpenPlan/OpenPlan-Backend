package com.openplan.backend.weeklyplan.domain;

import com.openplan.backend.rule.model.ReplanStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 재계획 대안 (SS-07~09) — V1 baseline {@code replan_options} 매핑. 한 전략의 재배치안을 저장한다.
 *
 * <p><b>KEEP_CURRENT(기준선)은 저장하지 않는다</b> — 현재 배치 그대로라 행이 없다(ck_replan_strategy에도 없음).
 * 재생성 시 그 주의 기존 대안을 전면 교체한다(SS-14 최신 대안만 의미). 선택(is_selected)·selected_at은 적용(PLAN-29) 시 기록.
 *
 * <p>{@code proposedBlocks}는 <b>JSONB</b>({@code proposed_blocks})다 — 선택 시 적용 원본(재배치할 TASK 위치 목록).
 * Hibernate 6 네이티브 {@code @JdbcTypeCode(SqlTypes.JSON)}로 매핑(추가 라이브러리 없음).
 */
@Getter
@Entity
@Table(name = "replan_options")
public class ReplanOption {

    @Id
    @Column(name = "replan_option_id")
    private UUID id;

    @Column(name = "weekly_plan_id", nullable = false, updatable = false)
    private UUID weeklyPlanId;

    @Column(name = "validation_issue_id")
    private UUID validationIssueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 30)
    private ReplanStrategy strategyType;

    @Column(name = "change_summary", columnDefinition = "text")
    private String changeSummary;

    @Column(name = "recommendation_reason", columnDefinition = "text")
    private String recommendationReason;

    @Column(name = "score")
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_blocks", nullable = false, columnDefinition = "jsonb")
    private List<StoredBlock> proposedBlocks;

    @Column(name = "is_selected", nullable = false)
    private boolean selected;

    @Column(name = "selected_at")
    private Instant selectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ReplanOption() {
    }

    /** JSONB 요소 — 재배치할 TASK 위치 1건(적용 시 블록 연산으로 번역). 전부 TASK라 blockType은 안 담는다. */
    public record StoredBlock(UUID taskId, Instant startAt, Instant endAt) {
    }

    /**
     * 생성 (재계획 대안 저장). is_selected=false, selected_at=null. score는 현재 null(명세 nullable — 산출식 미정).
     * createdAt은 서비스가 UserClock으로 주입(P-2).
     */
    public static ReplanOption create(UUID weeklyPlanId, ReplanStrategy strategyType, String changeSummary,
                                      String recommendationReason, List<StoredBlock> proposedBlocks, Instant createdAt) {
        ReplanOption o = new ReplanOption();
        o.id = UUID.randomUUID();
        o.weeklyPlanId = weeklyPlanId;
        o.validationIssueId = null;
        o.strategyType = strategyType;
        o.changeSummary = changeSummary;
        o.recommendationReason = recommendationReason;
        o.score = null;
        o.proposedBlocks = proposedBlocks;
        o.selected = false;
        o.selectedAt = null;
        o.createdAt = createdAt;
        return o;
    }

    /** 대안 선택 (PLAN-29 적용) — is_selected=true, selected_at 기록. */
    public void markSelected(Instant now) {
        this.selected = true;
        this.selectedAt = now;
    }

    /** 선택 해제 — 같은 계획에서 다른 대안이 선택될 때 기존 선택을 내린다(선택은 계획당 하나). */
    public void unselect() {
        this.selected = false;
        this.selectedAt = null;
    }
}
