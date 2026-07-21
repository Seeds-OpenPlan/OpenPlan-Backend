package com.openplan.backend.project.controller;

import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 생성 API 통합 테스트 (PROJ-02 / EP-2) — Testcontainers + 실제 필터 체인(dev-auth 스텁).
 * X-Dev-User 헤더 = 시드 사용자 …0001(DevUserAuthFilter.DEFAULT_DEV_USER).
 *
 * <p>검증 로직 경계값은 ProjectValidatorTest(단위)가 결정적으로 커버 — 여기선 배선·상태코드·봉투·규정값을 증명한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ProjectCreateApiTest {

    private static final String PATH = "/api/v1/projects";
    private static final String DEV_USER = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AC-02-1 정상 생성 → 201 · status=IN_PROGRESS · closedAt=null · version=0 · projectId 포함")
    void createOk() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"논문 작성","description":"석사 논문","dueDate":"2099-12-31","priority":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.projectId").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("논문 작성"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.closedAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("AC-02-2 name만 있고 나머지 생략 → 201")
    void createNameOnly() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"이름만 있는 프로젝트"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("이름만 있는 프로젝트"))
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
    }

    @Test
    @DisplayName("AC-02-3 name 공백만 → 422 E-COM-009")
    void blankNameRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   "}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    @Test
    @DisplayName("C2 — name 키 자체 부재 → 500 아니라 422 E-COM-009")
    void missingNameKeyIs422NotError() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"name 없음"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    @Test
    @DisplayName("AC-02-4 과거 due_date → 422 E-COM-009")
    void pastDueDateRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"지난 프로젝트","dueDate":"2000-01-01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    @Test
    @DisplayName("금지 필드 status 포함 → 400 E-COM-001 (침묵 무시 금지)")
    void forbiddenStatusFieldRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"상태 지정 시도","status":"CLOSED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("미시드 사용자 헤더 → 401 E-COM-002 (소유자 격리 배선)")
    void unseededUserUnauthorized() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", "99999999-9999-9999-9999-999999999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    @Test
    @DisplayName("성공 응답에 X-Trace-Id 헤더가 있다(관측성 배선)")
    void traceIdHeaderPresent() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Dev-User", DEV_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"트레이스 확인"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
