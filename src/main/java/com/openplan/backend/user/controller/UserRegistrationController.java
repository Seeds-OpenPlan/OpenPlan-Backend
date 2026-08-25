package com.openplan.backend.user.controller;

import com.openplan.backend.auth.service.EmailVerificationService;
import com.openplan.backend.global.mail.MailDeliveryException;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.user.dto.SignUpRequest;
import com.openplan.backend.user.dto.SignUpResponse;
import com.openplan.backend.user.service.UserRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>인증 메일 발송을 여기서 잇는다.</b> 2026-08-17 실측에서 가입이 토큰도 메일도 만들지 않아
 * {@code auth_tokens}가 0행이었다 — 가입해도 아무 메일이 오지 않는 상태였다. 정본(openapi 201
 * "계정 생성 → 이메일 인증 단계" · ST-B1-04 AC)이 첫 발송 주체를 정하지 않아 양쪽 다 안 하고 있었다.
 * 사용자 결정으로 <b>서버가 보낸다</b>로 확정했다.
 *
 * <p>서비스가 아니라 <b>컨트롤러가 잇는 이유</b>가 셋 있다.
 * <ul>
 *   <li>{@link EmailVerificationService}가 {@link UserRegistrationService}를 이미 참조하므로
 *       거꾸로 주입하면 순환이 된다.</li>
 *   <li>{@code signUp}의 트랜잭션이 <b>커밋된 뒤</b> 발송이 일어난다. 같은 트랜잭션 안에서 보내면
 *       SMTP 장애가 계정 생성을 통째로 되돌린다 — 메일은 재발송할 수 있지만 계정은 그렇지 않다.</li>
 *   <li>소셜 가입({@code registerSocial})에는 붙지 않는다. 제공자가 이미 검증한 주소라
 *       인증 메일을 보낼 이유가 없다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/users")
@Tag(name = "users", description = "회원가입 (AUTH-03)")
public class UserRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationController.class);

    private final UserRegistrationService userRegistrationService;
    private final EmailVerificationService emailVerificationService;

    public UserRegistrationController(UserRegistrationService userRegistrationService,
                                      EmailVerificationService emailVerificationService) {
        this.userRegistrationService = userRegistrationService;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping
    @Operation(summary = "회원가입 (AUTH-03)",
            description = "계정 생성 후 인증 메일을 발송한다(쿠키 미발급). "
                    + "중복 이메일 409 E-AUTH-003 · 비밀번호 규칙 위반 400 E-COM-001. "
                    + "메일 발송이 실패해도 계정은 생성되며 201로 답한다 — 재발송은 "
                    + "POST /auth/email-verifications.")
    public ResponseEntity<ApiResponse<SignUpResponse>> signUp(@Valid @RequestBody SignUpRequest request) {
        SignUpResponse response = userRegistrationService.signUp(request);
        sendVerificationMail(request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * 발송 실패를 삼키고 201을 유지한다.
     *
     * <p>계정은 이미 커밋됐다. 여기서 500을 내면 사용자는 <b>가입이 실패했다고 읽고</b> 다시 시도하는데,
     * 그때 받는 건 409 E-AUTH-003("이미 가입된 이메일")이다 — 성공한 가입을 실패로 보이게 만든 뒤
     * 재시도 경로까지 막는 셈이다. 화면은 어차피 "인증 메일을 보냈습니다 · 재발송" 상태로 가고,
     * 메일이 오지 않으면 사용자가 재발송을 누르면 된다.
     */
    private void sendVerificationMail(String email) {
        try {
            emailVerificationService.send(email);
        } catch (MailDeliveryException e) {
            log.error("가입 인증 메일 발송 실패 — 계정은 생성됨. 사용자가 재발송으로 복구 가능", e);
        }
    }
}
