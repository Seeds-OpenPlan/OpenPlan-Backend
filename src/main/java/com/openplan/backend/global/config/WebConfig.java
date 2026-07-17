package com.openplan.backend.global.config;

import com.openplan.backend.global.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC 설정 — {@code @CurrentUser} ArgumentResolver 등록 + 전 REST 컨트롤러 {@code /api/v1} 프리픽스.
 * CORS는 개방하지 않는다(단일 오리진 · Vite 프록시, api-contracts §1).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 전 API 공통 프리픽스 (api-contracts — 모든 경로가 /api/v1/...). */
    public static final String API_PREFIX = "/api/v1";

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // 우리 컨트롤러(com.openplan.backend)에만 프리픽스 — springdoc(/v3/api-docs) 등 라이브러리 컨트롤러는 제외
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forBasePackage("com.openplan.backend"));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
