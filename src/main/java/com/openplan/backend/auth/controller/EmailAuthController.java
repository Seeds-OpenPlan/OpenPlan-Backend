package com.openplan.backend.auth.controller;

import com.openplan.backend.auth.dto.CompletePasswordResetRequest;
import com.openplan.backend.auth.dto.ConfirmEmailVerificationRequest;
import com.openplan.backend.auth.dto.EmailAddressRequest;
import com.openplan.backend.auth.service.EmailVerificationService;
import com.openplan.backend.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일 인증·비밀번호 재설정 컨트롤러 (ST-B1-04/05 · AUTH-04/05/06).
 *
 * <p>네 EP 모두 <b>비인증 접근</b>이다({@code security: []}) — 로그인하지 못하는 사람이 쓰는 통로다.
 * {@code /api/v1/auth/**}가 이미 열려 있어 {@code SecurityConfig} 변경은 없다.
 *
 * <p><b>응답이 계정 존재 여부를 드러내지 않는다.</b> 발송 계열은 계정이 없어도 202이고,
 * 링크 계열은 모르는 토큰과 만료된 토큰을 같은 410으로 답한다. 자세한 근거는 각 서비스 javadoc에 있다.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth-email", description = "이메일 인증·비밀번호 재설정 (AUTH-04/05/06)")
public class EmailAuthController {

    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    public EmailAuthController(EmailVerificationService emailVerificationService,
                               PasswordResetService passwordResetService) {
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/email-verifications")
    @Operation(summary = "인증 메일 발송 (AUTH-04) — 재발송 겸용",
            description = "202 접수. 계정이 없거나 이미 인증된 경우에도 202(열거 방지). "
                    + "재발송 쿨다운에 걸리면 429 E-COM-007 + details.retryAfterSeconds.")
    public ResponseEntity<Void> sendEmailVerification(@Valid @RequestBody EmailAddressRequest request) {
        emailVerificationService.send(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email-verifications/confirmation")
    @Operation(summary = "이메일 인증 완료 (AUTH-04)",
            description = "멱등 — 이미 인증된 계정의 링크 재사용도 200. 모르는·만료된 링크는 410 E-AUTH-004.")
    public ResponseEntity<Void> confirmEmailVerification(
            @Valid @RequestBody ConfirmEmailVerificationRequest request) {
        emailVerificationService.confirm(request.token());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password-resets")
    @Operation(summary = "재설정 메일 요청 (AUTH-05)",
            description = "언제나 202 — 계정 존재 여부·소셜 여부·쿨다운과 무관하게 같은 응답(열거 방지).")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody EmailAddressRequest request) {
        passwordResetService.request(request.email());
        return ResponseEntity.accepted().build();
    }

    @PatchMapping("/password-resets/{token}")
    @Operation(summary = "재설정 완료 (AUTH-06)",
            description = "1회용(NFR-005). 모르는·만료된·이미 사용된 링크는 410 E-AUTH-006. "
                    + "성공 시 기존 세션을 전부 폐기한다.")
    public ResponseEntity<Void> completePasswordReset(@PathVariable String token,
                                                      @Valid @RequestBody CompletePasswordResetRequest request) {
        passwordResetService.complete(token, request.newPassword());
        return ResponseEntity.status(HttpStatus.OK).build();
    }
}
