package com.openplan.backend.global.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증 주체 주입 마커. 컨트롤러 파라미터에 {@code @CurrentUser UUID userId} 형태로 사용한다.
 *
 * <p>도메인 코드는 이 값만 본다 — JWT인지 dev 스텁인지 모른다(api-contracts §4.1-1).
 * 4주차 교체 시 인증 필터만 바뀌고 {@code @CurrentUser} 뒤 도메인 코드는 무변경(§4.2).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
