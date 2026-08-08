package com.openplan.backend.global.config;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.ErrorResponse;
import com.openplan.backend.global.security.DevSeededUserVerifier;
import com.openplan.backend.global.security.DevUserAuthFilter;
import com.openplan.backend.global.security.JwtCookieAuthFilter;
import com.openplan.backend.global.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 설정 (global — BE-1). 무상태 · CORS 미개방(단일 오리진 + Vite 프록시, api-contracts §1).
 *
 * <p>인증 주체 주입은 프로파일별 필터가 담당한다: dev(local)는 {@link DevUserAuthFilter}(스텁),
 * 운영({@code op.auth.dev-stub=false})은 {@link JwtCookieAuthFilter}가 같은 자리에 들어간다(D-16 · 4주차 이행 완료).
 * 둘 다 principal 로 UUID를 심으므로 {@code @CurrentUser} 뒤 도메인 코드는 주입원과 무관하다.
 * 미인증 접근은 오류 봉투 E-COM-002로 응답한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/",
            "/error",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/openapi.yaml",     // 정본 계약 — Swagger UI(springdoc.swagger-ui.url)가 읽는 대상. 빠지면 UI가 빈 화면
            "/api/v1/auth/**",   // 로그인·세션·소셜·가입 등 — 각 EP가 자체 인증 로직 처리(스텁: §2.1)
            "/api/v1/landing",   // 비인증 랜딩(가드 예외)
            // 도움말/FAQ 공개(openapi security:[] — 비로그인 접근). ST-B1-13 contract-change (post-freeze, 라벨).
            "/api/v1/support/help-articles",
            "/api/v1/support/help-articles/**",
            // 공지 공개(openapi security:[] — 비로그인 접근). ST-B1-14 contract-change (post-freeze, 라벨).
            "/api/v1/announcements",
            "/api/v1/announcements/**"
    };

    /**
     * dev-auth 스텁 활성 여부 (local 기본 true, 운영 override false — §4.2-2). 필터를 빈으로 노출하지 않고
     * 여기서 직접 생성해 Spring Boot 서블릿 체인 자동 등록(이중 실행)을 피한다.
     */
    private final boolean devStubEnabled;

    public SecurityConfig(@Value("${op.auth.dev-stub:false}") boolean devStubEnabled) {
        this.devStubEnabled = devStubEnabled;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                                  ErrorMessages errorMessages,
                                                  DevSeededUserVerifier userVerifier,
                                                  ObjectProvider<JwtService> jwtService) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint(objectMapper, errorMessages)));

        if (devStubEnabled) {
            http.addFilterBefore(new DevUserAuthFilter(objectMapper, errorMessages, userVerifier),
                    UsernamePasswordAuthenticationFilter.class);
        } else {
            // 4주차 교체 지점(D-16) — 이 클래스 javadoc이 예고한 자리다. 두 필터 모두 principal 로
            // UUID를 심으므로 @CurrentUser 뒤 도메인 코드는 한 줄도 바뀌지 않는다(무손실 교체).
            // JwtService 는 JwtAuthConfig 가 같은 조건(dev-stub=false)에서만 만들므로 여기서 반드시 존재한다.
            JwtService service = jwtService.getIfAvailable();
            if (service == null) {
                throw new IllegalStateException(
                        "op.auth.dev-stub=false 인데 JwtService 빈이 없습니다. JwtAuthConfig 등록 조건을 확인하십시오.");
            }
            http.addFilterBefore(new JwtCookieAuthFilter(service), UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    /** 미인증 → 오류 봉투 E-COM-002 (기본 401 HTML 대신 계약된 JSON 봉투). */
    private AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper, ErrorMessages errorMessages) {
        return (request, response, authException) -> {
            ErrorCode code = ErrorCode.E_COM_002;
            response.setStatus(code.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, errorMessages.resolve(code), null));
        };
    }
}
