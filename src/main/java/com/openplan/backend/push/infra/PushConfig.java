package com.openplan.backend.push.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 푸시 설정 바인딩 — {@link VapidProperties}. {@code AppConfig}와 같은 이유로 개별 등록한다
 * (전역 {@code @ConfigurationPropertiesScan}을 쓰지 않는 이 프로젝트의 선례).
 */
@Configuration
@EnableConfigurationProperties(VapidProperties.class)
public class PushConfig {
}
