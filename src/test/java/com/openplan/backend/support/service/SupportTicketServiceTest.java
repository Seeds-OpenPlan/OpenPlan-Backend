package com.openplan.backend.support.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.support.domain.SupportCategory;
import com.openplan.backend.support.domain.SupportTicket;
import com.openplan.backend.support.domain.SupportTicketStatus;
import com.openplan.backend.support.dto.CreateTicketRequest;
import com.openplan.backend.support.dto.SupportTicketResponse;
import com.openplan.backend.support.repository.SupportTicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 문의 서비스 단위 테스트(DB 불요). 등록·본인 상세·타인 404 은닉을 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private SupportTicketRepository repository;

    @InjectMocks
    private SupportTicketService service;

    @Test
    void createTicket_RECEIVED_답변없음으로_생성() {
        when(repository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportTicketResponse res = service.createTicket(USER_ID,
                new CreateTicketRequest("BUG", "로그인이 안 돼요", "이런 상황입니다"));

        assertThat(res.category()).isEqualTo(SupportCategory.BUG);
        assertThat(res.status()).isEqualTo(SupportTicketStatus.RECEIVED);
        assertThat(res.hasAnswer()).isFalse();
        assertThat(res.answerContent()).isNull();
        assertThat(res.title()).isEqualTo("로그인이 안 돼요");
    }

    @Test
    void getMyTicket_본인_티켓을_반환() {
        SupportTicket ticket = SupportTicket.create(USER_ID, SupportCategory.ACCOUNT, "제목", "내용");
        when(repository.findBySupportTicketIdAndUserId(ticket.getSupportTicketId(), USER_ID))
                .thenReturn(Optional.of(ticket));

        SupportTicketResponse res = service.getMyTicket(USER_ID, ticket.getSupportTicketId());

        assertThat(res.title()).isEqualTo("제목");
        assertThat(res.category()).isEqualTo(SupportCategory.ACCOUNT);
    }

    @Test
    void getMyTicket_타인_티켓은_404_은닉() {
        UUID otherTicketId = UUID.randomUUID();
        // (id, userId) 매칭 실패 → empty → 404 (403 아님)
        when(repository.findBySupportTicketIdAndUserId(otherTicketId, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyTicket(USER_ID, otherTicketId))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }
}
