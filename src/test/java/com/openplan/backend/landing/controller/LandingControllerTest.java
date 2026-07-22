package com.openplan.backend.landing.controller;

import com.openplan.backend.landing.service.LandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LandingController HTTP 계층 테스트(standalone MockMvc, DB 불요). 비인증 200·sections 배열 봉투를 확인한다.
 * (실제 가드 예외는 SecurityConfig 소관 — 부팅 테스트에서 검증.)
 */
class LandingControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LandingController(new LandingService())).build();
    }

    @Test
    void GET_landing_은_sections_배열_봉투_200() throws Exception {
        mockMvc.perform(get("/landing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections").isArray())
                .andExpect(jsonPath("$.data.sections.length()").value(0));
    }
}
