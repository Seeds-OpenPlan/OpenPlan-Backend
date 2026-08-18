package com.openplan.backend.user.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.user.dto.SignUpRequest;
import com.openplan.backend.user.dto.SignUpResponse;
import com.openplan.backend.user.service.UserRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원가입 컨트롤러 (ST-B1-04 · AUTH-03) — {@code POST /users}.
 *
 * <p>{@code /users/me} 계열(ST-B1-07 {@link UserController})과 경로가 갈리므로 컨트롤러를 분리한다.
 * 이쪽은 <b>비인증 접근</b>이고({@code SecurityConfig}가 POST 하나만 허용) 저쪽은 전부 인증 대상이라,
 * 보안 성격이 다른 둘을 한 클래스에 섞지 않는다.
 *
 * <p>경로는 {@code /api/v1} 접두 없이 매핑한다 — {@code WebConfig}가 부여한다.
 */
@RestController
@RequestMapping("/users")
@Tag(name = "users", description = "회원가입 (AUTH-03)")
public class UserRegistrationController {

    private final UserRegistrationService userRegistrationService;

    public UserRegistrationController(UserRegistrationService userRegistrationService) {
        this.userRegistrationService = userRegistrationService;
    }

    @PostMapping
    @Operation(summary = "회원가입 (AUTH-03)",
            description = "계정 생성 후 이메일 인증 단계로 넘어간다(쿠키 미발급). "
                    + "중복 이메일 409 E-AUTH-003 · 비밀번호 규칙 위반 400 E-COM-001.")
    public ResponseEntity<ApiResponse<SignUpResponse>> signUp(@Valid @RequestBody SignUpRequest request) {
        SignUpResponse response = userRegistrationService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
