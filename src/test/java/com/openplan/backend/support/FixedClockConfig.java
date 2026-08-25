package com.openplan.backend.support;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.time.UserClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 고정 시계 테스트 설정 — {@link UserClock}을 고정값 빈으로 덮어써 "오늘/지금"을 못 박는다.
 *
 * <p>자동종료(PROJ-08)처럼 날짜 경계를 다루는 로직은 실제 시계/서버 timezone에 의존하면
 * UTC·Asia/Seoul 날짜가 갈리는 시간대에 flaky해진다(실측 재현됨). 통합 테스트가 이 설정을
 * {@code @Import}하면 자동종료 판정이 실행 시각·환경과 무관하게 결정적이다(C4 seam의 올바른 사용).
 */
@TestConfiguration
public class FixedClockConfig {

    public static final LocalDate FIXED_TODAY = LocalDate.of(2026, 7, 15);
    public static final Instant FIXED_NOW = Instant.parse("2026-07-15T00:00:00Z");
    public static final Weekday FIXED_WEEK_START_DAY = Weekday.MON;
    public static final ZoneId FIXED_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    @Primary
    public UserClock fixedUserClock() {
        return new UserClock() {
            @Override
            public Instant now() {
                return FIXED_NOW;
            }

            @Override
            public LocalDate todayOf(UUID userId) {
                return FIXED_TODAY;
            }

            @Override
            public ZoneId zoneOf(UUID userId) {
                return FIXED_ZONE;
            }

            @Override
            public Weekday weekStartDayOf(UUID userId) {
                return FIXED_WEEK_START_DAY;
            }
        };
    }
}
