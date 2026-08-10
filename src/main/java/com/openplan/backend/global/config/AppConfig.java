package com.openplan.backend.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 앱 전역 설정 바인딩. 지금은 주소({@link AppProperties}) 하나뿐이다.
 *
 * <p>{@code @ConfigurationPropertiesScan}으로 일괄 바인딩하지 않는 이유는 이 프로젝트가
 * <b>조건부로만 등록되는 설정</b>을 갖기 때문이다({@code JwtProperties} — dev 스텁 로컬에서
 * 시크릿 검증에 걸리지 않아야 한다). 스캔을 켜면 그 조건이 우회된다.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {
}
