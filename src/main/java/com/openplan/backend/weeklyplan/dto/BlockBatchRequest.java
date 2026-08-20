package com.openplan.backend.weeklyplan.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * 블록 일괄 적용 요청 (RB-PLAN-01·PLAN-29 / 정본 openapi.yaml {@code applyBlockBatch}).
 *
 * <p>{@code operations}를 순서대로 한 트랜잭션에서 실행한다 — 하나라도 실패하면 전체 롤백(원자적).
 * 제안(자동배치)의 수용 = 사용자 확정 행위(P2)라, 낱개 API(생성·이동·삭제)를 묶어 한 번에 반영한다.
 */
public record BlockBatchRequest(
        @NotEmpty(message = "operations는 비어 있을 수 없습니다.") List<Operation> operations) {

    /**
     * 단위 연산. {@code op}에 따라 필요한 필드가 다르다:
     * <ul>
     *   <li><b>CREATE</b>: {@code block} 필수(이 계획에 새 블록 배치).</li>
     *   <li><b>MOVE</b>: {@code planBlockId} + {@code block} 필수(블록 시각 조정 — 정본 PlanBlockInput이라 주차 이동은 없다).</li>
     *   <li><b>DELETE</b>: {@code planBlockId} 필수(해제·삭제).</li>
     * </ul>
     * {@code op}은 String으로 받아 서비스가 검증한다(미정의값 파싱 500 회피 — 프로젝트 컨벤션).
     */
    public record Operation(String op, PlanBlockCreateRequest block, UUID planBlockId) {
    }
}
