package com.openplan.backend.global.config;

import com.openplan.backend.rule.engine.FirstFitPlacementEngine;
import com.openplan.backend.rule.engine.PlanValidationEngine;
import com.openplan.backend.rule.engine.ReplanEngine;
import com.openplan.backend.rule.port.PlanPlacementPort;
import com.openplan.backend.rule.port.PlanReplanPort;
import com.openplan.backend.rule.port.PlanValidationPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 규칙 엔진 접합(seam) — 순수 {@code rule/} 패키지를 스프링 컨텍스트에 올리는 유일한 지점.
 *
 * <p>엔진에 {@code @Component}를 직접 붙이지 않는다. {@code RuleEnginePurityTest}가
 * {@code rule/}의 {@code org.springframework..} 의존을 ArchUnit으로 금지하기 때문이다(C-1 결정성).
 * 프레임워크 지식은 이 클래스가 전부 흡수하고, 엔진은 스냅샷만 받는 순수 함수로 남는다.
 *
 * <p>소비자는 구현이 아니라 {@link PlanValidationPort} 계약에만 의존한다.
 */
@Configuration
public class RuleEngineConfig {

    @Bean
    public PlanValidationPort planValidationPort() {
        return new PlanValidationEngine();
    }

    @Bean
    public PlanPlacementPort planPlacementPort() {
        return new FirstFitPlacementEngine();
    }

    @Bean
    public PlanReplanPort planReplanPort() {
        return new ReplanEngine();
    }
}
