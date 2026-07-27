package com.openplan.backend.common;

/**
 * 요일 enum (공용) — 가용 시간(availability_patterns.weekday)·주 시작 요일 등 여러 도메인에서 쓰인다.
 *
 * <p>값 이름은 DB CHECK(ck_availability_weekday: MON..SUN)·openapi {@code Weekday} 스키마와 1:1 고정.
 * 이름이 곧 저장 문자열이므로 임의 변경 금지(VARCHAR(3) 한도도 이 이름들이 전제).
 *
 * <p>NOTE: ST-B1-07은 {@code user.domain.Weekday}를 별도 정의했다(그 브랜치가 이 공용 도입 전이었다).
 * 두 브랜치가 main에 합쳐진 뒤 user 쪽을 이 공용으로 통합하고 중복을 제거한다(추적 후속).
 */
public enum Weekday {
    MON, TUE, WED, THU, FRI, SAT, SUN
}
