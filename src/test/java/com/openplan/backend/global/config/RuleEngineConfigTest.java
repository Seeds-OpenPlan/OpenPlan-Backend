package com.openplan.backend.global.config;

import com.openplan.backend.rule.engine.PlanValidationEngine;
import com.openplan.backend.rule.port.PlanValidationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접합 배선 검증 — DB·Docker 없이 규칙 엔진 빈 정의만 올려본다.
 *
 * <p>전체 컨텍스트 테스트({@code OpenplanBackendApplicationTests})는 Flyway·PostgreSQL을 요구해
 * 로컬에서 Docker 없이는 돌지 않는다. 접합의 핵심(순수 엔진이 스프링 빈으로 주입 가능한가)은
 * DB와 무관하므로 여기서 독립적으로 증명한다.
 */
class RuleEngineConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(RuleEngineConfig.class);

    @Test
    @DisplayName("PlanValidationPort 가 구현이 아닌 계약 타입으로 주입 가능하다")
    void 포트가_빈으로_등록된다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PlanValidationPort.class);
            // 소비자는 구현을 몰라도 된다 — 주입 타입은 어디까지나 포트다.
            assertThat(context.getBean(PlanValidationPort.class)).isInstanceOf(PlanValidationEngine.class);
        });
    }
}
