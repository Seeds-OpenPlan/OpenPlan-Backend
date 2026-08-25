package com.openplan.backend.weeklyplan.domain;

import com.openplan.backend.rule.model.ValidationIssue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

/**
 * 영속된 검증 이슈 (ST-B2-09) — V1 baseline {@code validation_issues} 매핑.
 *
 * <p>엔진 판정({@link ValidationIssue})을 저장 표현으로 옮긴다. 테이블에는 {@code counterpartId}·{@code weekday}
 * 컬럼이 없으므로(스키마 §5) 그 두 축은 영속하지 않는다 — 목록 배지(PLAN-22)엔 type·severity·message로 충분하고,
 * 상세 판정은 검증 재실행 응답이 담는다. {@code wbsItemId}는 V5(미구현) 전까지 항상 null. created_at은 DB DEFAULT.
 */
@Getter
@Entity
@Table(name = "validation_issues")
public class ValidationIssueRecord {

    @Id
    @Column(name = "validation_issue_id")
    private UUID id;

    @Column(name = "weekly_plan_id", nullable = false, updatable = false)
    private UUID weeklyPlanId;

    @Column(name = "plan_block_id")
    private UUID planBlockId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "wbs_item_id")
    private UUID wbsItemId;

    @Column(name = "issue_type", nullable = false, length = 30)
    private String issueType;

    @Column(nullable = false, length = 10)
    private String severity;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "resolution_status", nullable = false, length = 10)
    private String resolutionStatus;

    /** JPA 전용. */
    protected ValidationIssueRecord() {
    }

    private ValidationIssueRecord(UUID id, UUID weeklyPlanId, UUID planBlockId, UUID taskId,
                                  String issueType, String severity, String message) {
        this.id = id;
        this.weeklyPlanId = weeklyPlanId;
        this.planBlockId = planBlockId;
        this.taskId = taskId;
        this.wbsItemId = null;
        this.issueType = issueType;
        this.severity = severity;
        this.message = message;
        this.resolutionStatus = "OPEN";
    }

    /** 엔진 판정 → 영속 레코드. id는 앱에서 부여(응답에 그대로 노출). */
    public static ValidationIssueRecord from(UUID weeklyPlanId, ValidationIssue i) {
        return new ValidationIssueRecord(
                UUID.randomUUID(), weeklyPlanId,
                i.planBlockId(), i.taskId(),
                i.ruleId().name(), i.severity().name(), i.reason());
    }
}
