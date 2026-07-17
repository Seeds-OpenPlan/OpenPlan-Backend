package com.openplan.backend.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 부여 필터 (exceptions.md §1 · §3 — 로그 대조용).
 *
 * <p>요청마다 traceId를 MDC에 심고 응답 헤더 {@code X-Trace-Id}로 노출한다. 오류 봉투에는
 * 넣지 않는다(문의 시 헤더값 첨부 유도). 인바운드 {@code X-Trace-Id}가 있으면 승계(게이트웨이 연계).
 * 가장 바깥 필터로 동작해야 이후 모든 로그가 traceId를 갖는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String traceId = (inbound != null && !inbound.isBlank())
                ? inbound.trim()
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
