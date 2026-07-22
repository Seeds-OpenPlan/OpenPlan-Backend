package com.openplan.backend.project.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * 프로젝트 목록 조회 쿼리 파라미터 (PROJ-01 / EP-1). {@code @ModelAttribute}로 바인딩된다.
 *
 * <p>page/size 규약은 여기서 Bean Validation으로 검증한다(@Min/@Max) — 위반·타입 불일치(page=abc)는
 * MethodArgumentNotValidException → GlobalExceptionHandler → 400 E-COM-001(AC-01-4). status 열거값 검증은
 * 서비스가 담당(미정의값 → 422, 전이 오류와 구분). raw @RequestParam은 비정수 입력이 500으로 새므로 쓰지 않는다.
 */
public class ProjectListQuery {

    @Min(value = 1, message = "page는 1 이상이어야 합니다.")
    private int page = 1;

    @Min(value = 1, message = "size는 1 이상이어야 합니다.")
    @Max(value = 100, message = "size는 100 이하여야 합니다.")
    private int size = 20;

    /** null/빈 값 = 전체. 쉼표 다중값(그룹) 또는 단일값(Q-H). 열거 검증은 서비스. */
    private List<String> status;

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

    public List<String> getStatus() {
        return status;
    }

    public void setStatus(List<String> status) {
        this.status = status;
    }
}
