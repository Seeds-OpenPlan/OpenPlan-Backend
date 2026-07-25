package com.openplan.backend.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;

/**
 * 미배치 태스크 조회 쿼리 파라미터 (PROJ-19 / EP-7). {@code @ModelAttribute}로 바인딩된다.
 *
 * <p>page/size 규약은 Bean Validation(@Min/@Max)으로 검증(위반 → 400). status는 <b>서비스가 검증</b>한다 —
 * 생략 시 UNASSIGNED 기본값(D-5c 완화), UNASSIGNED 외 값만 → 422 E-COM-009. @NotNull을 두지 않는 이유는
 * "생략 → 기본값" 이면서 잘못된 값은 400이 아니라 422여야 하기 때문(바인딩 enum 변환 500도 회피 — String 수신).
 */
@Getter
public class UnassignedTaskQuery {

    @Min(value = 1, message = "page는 1 이상이어야 합니다.")
    private int page = 1;

    @Min(value = 1, message = "size는 1 이상이어야 합니다.")
    @Max(value = 100, message = "size는 100 이하여야 합니다.")
    private int size = 20;

    private String status;

    public void setPage(int page) {
        this.page = page;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
