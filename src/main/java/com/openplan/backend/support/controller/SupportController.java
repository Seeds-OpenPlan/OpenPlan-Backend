package com.openplan.backend.support.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.response.PageMeta;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.support.dto.CreateTicketRequest;
import com.openplan.backend.support.dto.HelpArticleResponse;
import com.openplan.backend.support.dto.SupportTicketResponse;
import com.openplan.backend.support.service.HelpArticleService;
import com.openplan.backend.support.service.SupportTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 문의·도움말 컨트롤러(ST-B1-13 · HELP-01~06).
 *
 * <p>문의(/support/tickets)는 인증·본인 스코핑, 도움말(/support/help-articles)은 공개(SecurityConfig에
 * 공개 경로 추가 — 비로그인 FAQ 접근). 경로는 접두 없이 매핑하고 프레임워크가 {@code /api/v1}을 부여한다.
 */
@RestController
@Tag(name = "support", description = "문의·FAQ (HELP-01~06)")
public class SupportController {

    /** 페이지 크기 상한(openapi Size max) — 초과 요청은 이 값으로 클램프한다. */
    private static final int MAX_PAGE_SIZE = 100;

    private final SupportTicketService ticketService;
    private final HelpArticleService helpArticleService;

    public SupportController(SupportTicketService ticketService, HelpArticleService helpArticleService) {
        this.ticketService = ticketService;
        this.helpArticleService = helpArticleService;
    }

    @GetMapping("/support/tickets")
    @Operation(summary = "내 문의 목록 (HELP-01/02) — 본인 것만, 최신순")
    public ApiResponse<List<SupportTicketResponse>> listTickets(@CurrentUser UUID userId,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        // 1-base 요청을 Spring Data 0-base로. 범위 밖 값은 계약(min1/max100)에 맞춰 클램프.
        int pageIndex = Math.max(1, page) - 1;
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Page<SupportTicketResponse> result = ticketService.getMyTickets(userId, PageRequest.of(pageIndex, pageSize));
        return ApiResponse.ok(result.getContent(), PageMeta.from(result));
    }

    @PostMapping("/support/tickets")
    @Operation(summary = "문의 등록 (HELP-04/05)")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> createTicket(@CurrentUser UUID userId,
                                                                          @Valid @RequestBody CreateTicketRequest request) {
        SupportTicketResponse created = ticketService.createTicket(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/support/tickets/{ticketId}")
    @Operation(summary = "문의 상세 (HELP-03) — 타인 문의는 404 은닉")
    public ApiResponse<SupportTicketResponse> getTicket(@CurrentUser UUID userId, @PathVariable UUID ticketId) {
        return ApiResponse.ok(ticketService.getMyTicket(userId, ticketId));
    }

    @GetMapping("/support/help-articles")
    @Operation(summary = "FAQ/도움말 검색 (HELP-06) — 공개, 0건이면 빈 목록")
    public ApiResponse<List<HelpArticleResponse>> listHelpArticles(@RequestParam(required = false) String keyword,
                                                                  @RequestParam(required = false) String category) {
        return ApiResponse.ok(helpArticleService.search(keyword, category));
    }

    @GetMapping("/support/help-articles/{articleId}")
    @Operation(summary = "도움말 상세 (HELP-06) — 공개")
    public ApiResponse<HelpArticleResponse> getHelpArticle(@PathVariable UUID articleId) {
        return ApiResponse.ok(helpArticleService.getArticle(articleId));
    }
}
