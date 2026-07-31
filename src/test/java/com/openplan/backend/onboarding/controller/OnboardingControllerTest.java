package com.openplan.backend.onboarding.controller;

import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.GlobalExceptionHandler;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.onboarding.domain.OnboardingStep;
import com.openplan.backend.onboarding.dto.OnboardingContentItem;
import com.openplan.backend.onboarding.dto.OnboardingProgressResponse;
import com.openplan.backend.onboarding.dto.UpdateOnboardingProgressRequest;
import com.openplan.backend.onboarding.service.OnboardingContentService;
import com.openplan.backend.onboarding.service.OnboardingProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OnboardingController HTTP 계층 테스트(standalone MockMvc, DB·컨텍스트 불요).
 * 라우팅·봉투·step 검증(400 E-COM-001)을 확인한다. {@code @CurrentUser}는 고정 UUID로 대체.
 */
class OnboardingControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private OnboardingProgressService progressService;
    private OnboardingContentService contentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        progressService = mock(OnboardingProgressService.class);
        contentService = mock(OnboardingContentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OnboardingController(progressService, contentService))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorMessages()))
                .setCustomArgumentResolvers(fixedCurrentUserResolver())
                .build();
    }

    @Test
    void GET_progress_는_data_봉투로_상태를_반환한다() throws Exception {
        when(progressService.getMyProgress(USER_ID))
                .thenReturn(new OnboardingProgressResponse(true, false, false, false, false, false, null));

        mockMvc.perform(get("/users/me/onboarding-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.introDone").value(true))
                .andExpect(jsonPath("$.data.tutorialSampleProjectId").doesNotExist());
    }

    @Test
    void PATCH_progress_정상_요청은_200_data_봉투() throws Exception {
        when(progressService.updateProgress(eq(USER_ID), any(UpdateOnboardingProgressRequest.class)))
                .thenReturn(new OnboardingProgressResponse(true, true, false, false, false, false, null));

        mockMvc.perform(patch("/users/me/onboarding-progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introDone\":true,\"profileDone\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileDone").value(true));
    }

    @Test
    void GET_contents_INTRO는_카드_배열을_반환한다() throws Exception {
        when(contentService.getContents(OnboardingStep.INTRO)).thenReturn(List.of(
                new OnboardingContentItem(1, "계획 검증 — 저장 전에 압니다", "본문1", null),
                new OnboardingContentItem(2, "일정 관리 — 프로젝트에서 회고까지", "본문2", null)));

        mockMvc.perform(get("/onboarding/contents").param("step", "INTRO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].order").value(1))
                .andExpect(jsonPath("$.data[0].title").value("계획 검증 — 저장 전에 압니다"));
    }

    @Test
    void GET_contents_잘못된_step은_400_E_COM_001() throws Exception {
        mockMvc.perform(get("/onboarding/contents").param("step", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"))
                .andExpect(jsonPath("$.error.details.field").value("step"));
    }

    @Test
    void GET_contents_step_누락은_400_E_COM_001() throws Exception {
        mockMvc.perform(get("/onboarding/contents"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    /** @CurrentUser UUID 파라미터에 고정 테스트 사용자 UUID를 주입(SecurityContext 우회). */
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
