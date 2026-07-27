package com.openplan.backend.availability.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openplan.backend.availability.dto.AvailabilityPatternDto;
import com.openplan.backend.availability.dto.AvailabilityView;
import com.openplan.backend.availability.dto.SaveAvailabilitiesRequest;
import com.openplan.backend.availability.service.AvailabilityService;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.GlobalExceptionHandler;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AvailabilityController HTTP 계층 테스트(standalone MockMvc, DB 불요). 라우팅·봉투·개수 검증(400)·
 * 서비스 검증 예외(422) 매핑을 확인한다. LocalTime 직렬화를 위해 JavaTimeModule을 명시 설정한다.
 */
class AvailabilityControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private AvailabilityService service;
    private MockMvc mockMvc;
    // production(Spring Boot)과 동일하게 날짜/시간을 ISO 문자열로 직렬화(타임스탬프 배열 비활성).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        service = mock(AvailabilityService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AvailabilityController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorMessages()))
                .setCustomArgumentResolvers(fixedCurrentUserResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void GET_availabilities_는_뷰를_data_봉투로_반환한다() throws Exception {
        when(service.getMyAvailabilities(USER_ID)).thenReturn(new AvailabilityView(
                List.of(new AvailabilityPatternDto(Weekday.MON, LocalTime.of(9, 0), LocalTime.of(18, 0), true)),
                540));

        mockMvc.perform(get("/users/me/availabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weeklyTotalMinutes").value(540))
                .andExpect(jsonPath("$.data.patterns[0].weekday").value("MON"))
                // JavaTimeModule ISO 직렬화(타임스탬프 비활성) — LocalTime은 "HH:mm:ss". production ObjectMapper도 동일.
                .andExpect(jsonPath("$.data.patterns[0].startTime").value("09:00:00"));
    }

    @Test
    void PUT_availabilities_정상_7행은_200() throws Exception {
        when(service.saveAvailabilities(eq(USER_ID), any(SaveAvailabilitiesRequest.class)))
                .thenReturn(new AvailabilityView(List.of(), 3780));

        mockMvc.perform(put("/users/me/availabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SaveAvailabilitiesRequest(sevenActive()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weeklyTotalMinutes").value(3780));
    }

    @Test
    void PUT_availabilities_행이_7개가_아니면_400_E_COM_001() throws Exception {
        List<AvailabilityPatternDto> six = new ArrayList<>(sevenActive());
        six.remove(0); // 6행

        mockMvc.perform(put("/users/me/availabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SaveAvailabilitiesRequest(six))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    void PUT_availabilities_서비스_검증실패는_422_E_COM_009로_매핑() throws Exception {
        when(service.saveAvailabilities(eq(USER_ID), any(SaveAvailabilitiesRequest.class)))
                .thenThrow(new OpenPlanException(ErrorCode.E_COM_009, Map.of("field", "patterns", "rule", "step")));

        mockMvc.perform(put("/users/me/availabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SaveAvailabilitiesRequest(sevenActive()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.rule").value("step"));
    }

    private static List<AvailabilityPatternDto> sevenActive() {
        List<AvailabilityPatternDto> list = new ArrayList<>();
        for (Weekday w : Weekday.values()) {
            list.add(new AvailabilityPatternDto(w, LocalTime.of(9, 0), LocalTime.of(18, 0), true));
        }
        return list;
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
