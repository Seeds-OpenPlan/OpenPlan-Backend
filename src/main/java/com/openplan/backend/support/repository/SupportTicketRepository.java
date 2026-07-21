package com.openplan.backend.support.repository;

import com.openplan.backend.support.domain.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 문의 리포지토리. 목록은 본인 것만 최신순(A12 인덱스), 상세는 (id, userId)로 조회해 타인 티켓을 404로 은닉한다.
 */
public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    Page<SupportTicket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<SupportTicket> findBySupportTicketIdAndUserId(UUID supportTicketId, UUID userId);
}
