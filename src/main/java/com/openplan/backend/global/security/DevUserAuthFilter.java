package com.openplan.backend.global.security;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * dev-auth 스텁 필터 (D-16 · ADR-0010 · api-contracts §4.1) — dev(local) 프로파일 한정.
 *
 * <ul>
 *   <li>{@code X-Dev-User} 헤더 생략 → 고정 dev 사용자({@link #DEFAULT_DEV_USER})</li>
 *   <li>헤더에 UUID 지정 → 그 사용자로 동작(사용자 격리·404 은닉 테스트 — NFR-030)</li>
 *   <li>형식 오류 UUID → 401 E-COM-002</li>
 * </ul>
 *
 * <p>도메인 코드는 {@code @CurrentUser}만 본다 — 이 필터는 주체 주입만 대신한다. 4주차 교체 시
 * 이 필터를 제거(또는 {@code op.auth.dev-stub=false})해도 {@code @CurrentUser} 뒤 코드는 무변경(§4.2).
 *
 * <p>TODO(ST-B1-01b): 시드 사용자 존재 검증(미시드 UUID → 401) — UserRepository 연결 후.
 */
public class DevUserAuthFilter extends OncePerRequestFilter {

    /** 고정 dev 사용자 (api-contracts §4.1-2). */
    public static final UUID DEFAULT_DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String HEADER = "X-Dev-User";

    private final ObjectMapper objectMapper;

    public DevUserAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        UUID userId;
        if (header == null || header.isBlank()) {
            userId = DEFAULT_DEV_USER;
        } else {
            try {
                userId = UUID.fromString(header.trim());
            } catch (IllegalArgumentException ex) {
                writeUnauthorized(response);
                return;
            }
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, AuthorityUtils.NO_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.E_COM_002;
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(code, code.defaultMessage(), null));
    }
}
