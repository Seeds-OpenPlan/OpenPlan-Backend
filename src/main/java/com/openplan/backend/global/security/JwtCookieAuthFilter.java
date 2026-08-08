package com.openplan.backend.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 쿠키 인증 필터 (D-16 4주차 실구현) — {@link DevUserAuthFilter}가 있던 <b>바로 그 자리</b>에 들어간다.
 * {@code SecurityConfig} javadoc이 예고한 교체 지점이다.
 *
 * <p><b>계약: principal 은 UUID 다.</b> dev 스텁이 심던 것과 동일한 모양이므로
 * {@link CurrentUserArgumentResolver}·{@code @CurrentUser}·전 도메인 코드가 <b>한 줄도 바뀌지 않는다</b>.
 * D-16이 노린 무손실 교체가 여기서 성립한다 — 진소희의 {@code findByIdAndUserId} 소유자 격리도 그대로 산다.
 *
 * <p><b>토큰이 없거나 무효여도 401을 직접 쓰지 않는다.</b> 그냥 인증을 심지 않고 통과시키면,
 * 보호 경로는 {@code SecurityConfig}의 entryPoint가 <b>계약된 봉투 E-COM-002</b>로 응답하고,
 * 공개 경로({@code PUBLIC_PATHS})는 정상 동작한다. 필터가 직접 401을 쓰면 공개 경로까지 막힌다.
 *
 * <p>dev 스텁과 달리 <b>사용자 존재 검증(DB 조회)을 하지 않는다.</b> 서명이 위조 불가를 이미 보증하고,
 * 매 요청 조회는 무상태 access 의 이점을 없앤다. 탈퇴·정지 반영은 access 수명(짧게)과
 * refresh 회전 시점의 검사로 흡수한다 — 그 지연이 곧 {@code accessTtl} 이다.
 */
public class JwtCookieAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtCookieAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<UUID> userId = readAccessCookie(request).flatMap(jwtService::parseAccessToken);

        if (userId.isEmpty()) {
            chain.doFilter(request, response);   // 미인증 → entryPoint 가 E-COM-002 로 마무리
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId.get(), null, AuthorityUtils.NO_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            // 스레드 재사용 환경에서 주체가 다음 요청으로 새는 것을 막는다(dev 필터와 동일 처리).
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<String> readAccessCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (AuthCookies.ACCESS.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }
}
