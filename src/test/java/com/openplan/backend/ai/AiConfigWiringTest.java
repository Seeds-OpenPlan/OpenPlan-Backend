package com.openplan.backend.ai;

import com.openplan.backend.global.config.RuleEngineConfig;
import com.openplan.backend.rule.engine.FirstFitPlacementEngine;
import com.openplan.backend.rule.port.PlanPlacementPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 접합의 <b>스프링 배선</b>을 실제 컨텍스트에서 검증한다 (조건 · @Primary · @Qualifier).
 *
 * <p><b>왜 필요한가.</b> 나머지 AI 테스트는 {@code new AiConfig()} 로 직접 조립하거나 클라이언트를
 * 대역으로 바꾼다 — 어느 것도 <b>스프링이 이 설정을 어떻게 올리는지</b> 는 증명하지 못한다. 실제로
 * 이 계층에서 결함이 나왔다: {@code @ConditionalOnProperty} 가 빈 문자열을 "설정됨" 으로 통과시켜
 * AI 접합이 켜진 채 매 요청이 실패→폴백을 반복하던 것을 수동 검증으로 발견했다. 그 종류를 자동으로 잡는다.
 *
 * <p>{@code @SpringBootTest} 를 쓰지 않는 이유는 DB(Testcontainers)를 끌어오지 않기 위해서다.
 * {@link ApplicationContextRunner} 는 조건·빈 우선순위만 필요한 만큼 올려 몇 밀리초에 끝난다.
 */
class AiConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RuleEngineConfig.class, AiConfig.class);

    @Test
    @DisplayName("base-url 이 있으면 AI 어댑터가 PlanPlacementPort 를 맡는다")
    void 주소가_있으면_AI가_맡는다() {
        runner.withPropertyValues("op.ai.base-url=http://openplan-ai:8000")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // @Primary 가 규칙 빈보다 우선하는지 — 타입으로 주입받는 소비자가 보는 것이 이것이다.
                    assertThat(ctx.getBean(PlanPlacementPort.class)).isInstanceOf(AiPlacementAdapter.class);
                    // 규칙 빈은 지워지지 않고 폴백으로 남아 있어야 한다(계약 §4 "폴백이 계약의 일부다").
                    assertThat(ctx.getBean("planPlacementPort")).isInstanceOf(FirstFitPlacementEngine.class);
                });
    }

    @Test
    @DisplayName("base-url 이 비어 있으면 접합이 안 올라가고 규칙이 그대로 맡는다")
    void 빈_문자열이면_규칙이_맡는다() {
        // 🔴 이것이 실제로 났던 결함이다. application.yaml 이 ${AI_BASE_URL:} 로 두므로 환경변수가
        //    없으면 값은 "빈 문자열로 존재" 한다. @ConditionalOnProperty 는 존재만 보고 통과시킨다.
        runner.withPropertyValues("op.ai.base-url=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(PlanPlacementPort.class)).isInstanceOf(FirstFitPlacementEngine.class);
                    assertThat(ctx).doesNotHaveBean(AiPlacementAdapter.class);
                });
    }

    @Test
    @DisplayName("속성 자체가 없어도 규칙이 맡는다 — AI 를 안 켠 로컬·CI 가 이것 때문에 멈추지 않는다")
    void 속성이_없어도_기동한다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PlanPlacementPort.class)).isInstanceOf(FirstFitPlacementEngine.class);
        });
    }
}
