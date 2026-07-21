package com.openplan.backend.support.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.support.domain.SupportCategory;
import com.openplan.backend.support.domain.SupportTicket;
import com.openplan.backend.support.dto.CreateTicketRequest;
import com.openplan.backend.support.dto.SupportTicketResponse;
import com.openplan.backend.support.repository.SupportTicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 문의 서비스(ST-B1-13) — 등록·본인 목록·본인 상세. 모든 조회는 userId로 스코핑하며,
 * 타인 티켓은 존재를 숨겨 404로 응답한다(NFR-030).
 */
@Service
public class SupportTicketService {

    private final SupportTicketRepository repository;

    public SupportTicketService(SupportTicketRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SupportTicketResponse createTicket(UUID userId, CreateTicketRequest request) {
        // category는 @Pattern으로 이미 값 집합이 강제됨 — 안전하게 enum 변환.
        SupportCategory category = SupportCategory.valueOf(request.category());
        SupportTicket ticket = repository.save(
                SupportTicket.create(userId, category, request.title(), request.content()));
        return SupportTicketResponse.from(ticket);
    }

    @Transactional(readOnly = true)
    public Page<SupportTicketResponse> getMyTickets(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(SupportTicketResponse::from);
    }

    @Transactional(readOnly = true)
    public SupportTicketResponse getMyTicket(UUID userId, UUID ticketId) {
        // (id, userId) 동시 조건 — 타인 티켓은 조회 결과 없음 → 404 은닉(403 아님).
        SupportTicket ticket = repository.findBySupportTicketIdAndUserId(ticketId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        return SupportTicketResponse.from(ticket);
    }
}
