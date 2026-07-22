package com.openplan.backend.announcement.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openplan.backend.announcement.domain.AnnouncementType;
import com.openplan.backend.announcement.dto.AnnouncementResponse;
import com.openplan.backend.announcement.service.AnnouncementService;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.GlobalExceptionHandler;
import com.openplan.backend.global.error.OpenPlanException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AnnouncementController HTTP 계층 테스트(standalone MockMvc, DB 불요). 공개 목록·page 메타·0건 200·
 * 상세 봉투(announcementType 포함)·미게시 404 은닉을 확인한다. Instant 직렬화를 위해 JavaTimeModule 설정.
 */
class AnnouncementControllerTest {

    private AnnouncementService service;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        service = mock(AnnouncementService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnnouncementController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorMessages()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void GET_announcements_는_목록과_page_메타() throws Exception {
        when(service.getPublished(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/announcements").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].announcementType").value("MAINTENANCE"))
                .andExpect(jsonPath("$.meta.page.number").value(1))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1));
    }

    @Test
    void GET_announcements_는_0건도_200() throws Exception {
        when(service.getPublished(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page.totalElements").value(0));
    }

    @Test
    void GET_announcement_상세는_data_봉투에_유형_포함() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getPublished(eq(id))).thenReturn(sample());

        mockMvc.perform(get("/announcements/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("정기 점검 안내"))
                .andExpect(jsonPath("$.data.announcementType").value("MAINTENANCE"))
                .andExpect(jsonPath("$.data.content").value("본문"));
    }

    @Test
    void GET_announcement_미게시_또는_미존재는_404_E_COM_004() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getPublished(eq(id))).thenThrow(new OpenPlanException(ErrorCode.E_COM_004));

        mockMvc.perform(get("/announcements/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    private static AnnouncementResponse sample() {
        return new AnnouncementResponse(UUID.randomUUID(), AnnouncementType.MAINTENANCE,
                "정기 점검 안내", "본문", Instant.parse("2026-07-21T00:00:00Z"));
    }
}
