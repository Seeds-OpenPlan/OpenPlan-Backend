package com.openplan.backend.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 프로젝트 내 태스크 목록 조회 쿼리 파라미터 (PROJ-16 / EP-1). {@code @ModelAttribute}로 바인딩된다.
 *
 * <p>page/size 규약은 여기서 Bean Validation(@Min/@Max)으로 검증한다 — 위반·비정수(page=abc)는
 * MethodArgumentNotValidException → 400 E-COM-001(AC-L-3). raw {@code @RequestParam}은 비정수가 500으로
 * 새므로 쓰지 않는다(ST-B2-01 규범).
 *
 * <p>status는 <b>단건 열거값</b>(D-9 — 프로젝트의 쉼표 그룹과 달리 탭 매핑 개별 필터). null/빈 값 = 전체.
 * 열거 검증은 서비스가 담당한다(미정의값 → 422 E-COM-009). 바인딩 단계에서 enum으로 받으면 미정의값이
 * 500으로 새므로 String으로 수신한다.
 */
public class TaskListQuery {

    @Min(value = 1, message = "page는 1 이상이어야 합니다.")
    private int page = 1;

    @Min(value = 1, message = "size는 1 이상이어야 합니다.")
    @Max(value = 100, message = "size는 100 이하여야 합니다.")
    private int size = 20;

    private String status;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
