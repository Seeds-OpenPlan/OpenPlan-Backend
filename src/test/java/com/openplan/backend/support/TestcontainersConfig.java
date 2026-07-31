package com.openplan.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 공용 Testcontainers 설정 (ST-B2-01 C3). 통합 테스트가 {@code @Import}로 공유한다.
 *
 * <p>이미지 = {@code pgvector/pgvector:pg16} — V1__baseline.sql이 {@code CREATE EXTENSION vector}를
 * 실행하므로 순정 postgres 이미지로는 Flyway V1이 실패한다(docker-compose와 동일 이미지).
 * {@code @ServiceConnection}이 datasource url/username/password를 컨테이너 값으로 자동 주입한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }
}
