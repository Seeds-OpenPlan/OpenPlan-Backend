package com.openplan.backend.category.dto;

/**
 * 카테고리 생성 요청 (ST-B2-04 / SC-01).
 *
 * <p>name은 <b>@NotBlank를 두지 않는다</b> — null/공백/길이 규칙은 서비스({@code TaskCategoryValidator})가
 * 422 E-COM-009로 판정한다(Bean Validation은 400을 내므로 값 규칙 위반의 422와 충돌). 키 부재 시 record가
 * null로 역직렬화되므로 Validator 첫 줄 null 가드가 필수다.
 */
public record TaskCategoryCreateRequest(String name) {
}