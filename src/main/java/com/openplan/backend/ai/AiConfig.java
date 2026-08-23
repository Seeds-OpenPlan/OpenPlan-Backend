package com.openplan.backend.ai;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openplan.backend.rule.port.PlanPlacementPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * AI 접합(seam) — 규칙 엔진의 {@code RuleEngineConfig} 와 같은 역할을 AI 쪽에서 한다.
 *
 * <p><b>{@code op.ai.base-url} 이 있을 때만 좌석이 생긴다.</b> 없으면 이 설정 전체가 안 올라가고
 * {@code RuleEngineConfig} 의 first-fit 이 그대로 {@link PlanPlacementPort} 를 맡는다 — AI 를 안 켠
 * 로컬·CI 가 이 때문에 멈추지 않는다(D-32 와 같은 판단).
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
// 🔴 @ConditionalOnProperty 를 쓰면 안 된다 — 그것은 "값이 있음"만 보고 빈 문자열도 통과시킨다.
//    application.yaml 이 base-url 을 ${AI_BASE_URL:} 로 두므로, 환경변수가 없으면 값은 "빈 문자열로
//    존재"한다. 그러면 AI 접합이 켜진 채 매 요청이 실패→폴백을 반복한다(동작은 하되 헛돈다).
@ConditionalOnExpression("!'${op.ai.base-url:}'.isEmpty()")
public class AiConfig {

    /**
     * 🔴 <b>앱 공용 ObjectMapper 를 쓰면 안 된다.</b> AI 계약 §3 의 표기 규약이 우리 API 계약과 다르다:
     * <ul>
     *   <li>요일 — 계약은 {@code "MON"}, Jackson 기본은 {@code "MONDAY"}</li>
     *   <li>시각(TIME) — 계약은 {@code "HH:mm"}, Jackson 기본은 {@code "09:00:00"}</li>
     * </ul>
     * AI 쪽은 이 둘을 각각 {@code Literal["MON",…]} 과 {@code ^([01]\d|2[0-3]):[0-5]\d$} 로 <b>엄격히</b>
     * 검증한다 — 기본 직렬화로 보내면 두 필드 모두 422 로 튕긴다. 공용 매퍼를 고치면 우리 openapi 응답까지
     * 바뀌므로, 이 접합에서만 쓰는 매퍼를 따로 둔다.
     *
     * <p>{@code Instant} 는 기본이 이미 {@code …Z} 라 그대로 두면 된다(계약: UTC 오프셋 0 강제).
     */
    @Bean
    ObjectMapper aiObjectMapper() {
        SimpleModule contract = new SimpleModule();
        contract.addSerializer(DayOfWeek.class, new JsonSerializer<>() {
            @Override
            public void serialize(DayOfWeek value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value.name().substring(0, 3)); // MONDAY → MON
            }
        });
        contract.addSerializer(LocalTime.class, new JsonSerializer<>() {
            private final DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");

            @Override
            public void serialize(LocalTime value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value.format(hhmm));
            }
        });
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(contract)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 타임아웃이 곧 폴백 시점이다 — 여기서 끊겨야 규칙 first-fit 이 돈다. 계약 §7 #2 제안값 20초.
     * 규칙 자동배치 예산(NFR-029 5초)보다 긴 것은 의도된 것이다.
     */
    @Bean
    RestClient aiRestClient(AiProperties properties, ObjectMapper aiObjectMapper) {
        // 🔴 HTTP/1.1 로 못박는다. JDK HttpClient 는 기본이 HTTP/2 라, 평문 http 에서는 먼저
        //    h2c 업그레이드를 시도한다(Connection: Upgrade). uvicorn(h11)은 그걸 지원하지 않아
        //    거부하는데, 그 과정에서 **본문이 사라진다** — FastAPI 는 body 가 없다고 보고
        //    422 {"loc":["body"],"msg":"Field required"} 를 돌려준다.
        //    2026-08-23 실측: 같은 요청을 curl 로 보내면 200, JDK 기본 설정으로 보내면 422.
        //    컨테이너 로그에 "Unsupported upgrade request" 가 함께 찍히는 것이 판별식이다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(aiObjectMapper));
                })
                .build();
    }

    @Bean
    AiPlanDraftClient aiPlanDraftClient(RestClient aiRestClient) {
        return new AiPlanDraftClient(aiRestClient);
    }

    /**
     * AI 를 {@link PlanPlacementPort} 앞단에 앉힌다. 규칙 빈({@code planPlacementPort})은 그대로 남아
     * 폴백으로 주입된다 — 지우는 것이 아니라 <b>뒤에 세우는</b> 것이다(계약 §4 "폴백이 계약의 일부다").
     */
    @Bean
    @Primary
    PlanPlacementPort aiPlanPlacementPort(AiPlanDraftClient client,
                                          @Qualifier("planPlacementPort") PlanPlacementPort ruleFallback) {
        return new AiPlacementAdapter(client, ruleFallback);
    }
}
