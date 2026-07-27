package com.openplan.backend.support.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.GlobalExceptionHandler;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.support.domain.SupportCategory;
import com.openplan.backend.support.domain.SupportTicketStatus;
import com.openplan.backend.support.dto.SupportTicketResponse;
import com.openplan.backend.support.service.HelpArticleService;
import com.openplan.backend.support.service.SupportTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SupportController HTTP 계층 테스트(standalone MockMvc, DB 불요). 등록(201)·목록 페이지 메타·404 은닉·
 * 도움말 공개 목록·category 검증(400)을 확인한다. Instant 직렬화를 위해 JavaTimeModule 설정.
 */
class SupportControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SupportTicketService ticketService;
    private HelpArticleService helpArticleService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        ticketService = mock(SupportTicketService.class);
        helpArticleService = mock(HelpArticleService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SupportController(ticketService, helpArticleService))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorMessages()))
                .setCustomArgumentResolvers(fixedCurrentUserResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void POST_ticket_은_201_data_봉투() throws Exception {
        when(ticketService.createTicket(eq(USER_ID), any())).thenReturn(sampleTicket());

        mockMvc.perform(post("/support/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BUG\",\"title\":\"제목\",\"content\":\"내용\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.category").value("BUG"))
                .andExpect(jsonPath("$.data.hasAnswer").value(false));
    }

    @Test
    void POST_ticket_잘못된_category는_400_E_COM_001() throws Exception {
        mockMvc.perform(post("/support/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WRONG\",\"title\":\"제목\",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    void GET_tickets_는_목록과_page_메타() throws Exception {
        when(ticketService.getMyTickets(eq(USER_ID), any()))
                .thenReturn(new PageImpl<>(List.of(sampleTicket()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/support/tickets").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.page.number").value(1))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1));
    }

    @Test
    void GET_ticket_타인_문의는_404() throws Exception {
        UUID id = UUID.randomUUID();
        when(ticketService.getMyTicket(eq(USER_ID), eq(id)))
                .thenThrow(new OpenPlanException(ErrorCode.E_COM_004));

        mockMvc.perform(get("/support/tickets/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    void GET_help_articles_는_공개_목록_0건도_200() throws Exception {
        when(helpArticleService.search(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/support/help-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private static SupportTicketResponse sampleTicket() {
        return new SupportTicketResponse(UUID.randomUUID(), SupportCategory.BUG, "제목", "내용",
                SupportTicketStatus.RECEIVED, false, null, null, Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static HandlerMethodArgumentResolver fixedCurrentUserResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(CurrentUser.class)
                        && parameter.getParameterType().equals(UUID.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return USER_ID;
            }
        };
    }
}
