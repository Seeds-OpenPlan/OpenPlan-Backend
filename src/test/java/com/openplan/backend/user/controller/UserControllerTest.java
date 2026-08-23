package com.openplan.backend.user.controller;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.GlobalExceptionHandler;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.user.domain.LoginType;
import com.openplan.backend.user.domain.SocialProvider;
import com.openplan.backend.user.domain.Weekday;
import com.openplan.backend.user.dto.UpdateProfileRequest;
import com.openplan.backend.user.dto.UserProfileResponse;
import com.openplan.backend.user.service.UserProfileService;
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

import java.util.Map;
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
 * UserController HTTP 계층 테스트(DB·스프링 컨텍스트 불요 — standalone MockMvc).
 *
 * <p>라우팅·성공/오류 봉투·@Valid 실패 매핑을 검증한다. {@code @CurrentUser}는 SecurityContext 대신
 * 고정 UUID를 주입하는 테스트용 리졸버로 대체한다(시큐리티 배선은 이 테스트의 관심사가 아니다).
 * 오류 봉투는 실제 {@link GlobalExceptionHandler}+{@link ErrorMessages}(실 카탈로그 로드)로 확인한다.
 */
class UserControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UserProfileService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(UserProfileService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(service, mock(com.openplan.backend.user.service.AccountDeactivationService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorMessages()))
                .setCustomArgumentResolvers(fixedCurrentUserResolver())
                .build();
    }

    @Test
    void GET_users_me_는_data_봉투로_프로필을_반환한다() throws Exception {
        when(service.getMyProfile(USER_ID)).thenReturn(sampleProfile());

        mockMvc.perform(get("/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.email").value("a@ex.com"))
                .andExpect(jsonPath("$.data.loginType").value("SOCIAL"))
                .andExpect(jsonPath("$.data.socialProvider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.weekStartDay").value("MON"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void PATCH_profile_정상_요청은_200_data_봉투() throws Exception {
        when(service.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class))).thenReturn(sampleProfile());

        mockMvc.perform(patch("/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"지훈\",\"weekStartDay\":\"MON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("지훈"));
    }

    @Test
    void PATCH_profile_잘못된_weekStartDay는_400_E_COM_001() throws Exception {
        // @Valid(@Pattern) 실패 → MethodArgumentNotValidException → E-COM-001 (서비스 미호출)
        mockMvc.perform(patch("/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekStartDay\":\"FUNDAY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void PATCH_profile_공백_이름은_400_E_COM_001() throws Exception {
        mockMvc.perform(patch("/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    void PATCH_profile_실재하지_않는_시간대는_서비스예외를_422_봉투로_매핑() throws Exception {
        when(service.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class)))
                .thenThrow(new OpenPlanException(ErrorCode.E_COM_009, Map.of("field", "timezone")));

        mockMvc.perform(patch("/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Asia/Nowhere\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.field").value("timezone"));
    }

    private static UserProfileResponse sampleProfile() {
        return new UserProfileResponse(USER_ID, "a@ex.com", LoginType.SOCIAL, SocialProvider.GOOGLE,
                "지훈", "취준", "Asia/Seoul", Weekday.MON);
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
