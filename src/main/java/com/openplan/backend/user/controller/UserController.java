package com.openplan.backend.user.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.user.dto.ChangePasswordRequest;
import com.openplan.backend.user.dto.UpdateProfileRequest;
import com.openplan.backend.user.dto.DeactivationResponse;
import com.openplan.backend.user.dto.UserProfileResponse;
import com.openplan.backend.user.service.AccountDeactivationService;
import com.openplan.backend.user.service.PasswordChangeService;
import com.openplan.backend.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 내 정보·프로필 컨트롤러(ST-B1-07 · ACCT-01 · ONB-02).
 *
 * <p>경로는 접두 없이 매핑하고 프레임워크가 {@code /api/v1}을 부여한다(AuthController와 동일 관례).
 * 인증 주체는 {@code @CurrentUser}로만 받는다(D-16 — 스텁/JWT 무관). DELETE /users/me(비활성화, ACCT-04)와
 * PATCH /users/me/password(ACCT-02)도 같은 주체 스코프이므로 여기서 함께 다룬다.
 */
@RestController
@RequestMapping("/users/me")
@Tag(name = "user", description = "내 정보·프로필 (ACCT-01 · ONB-02)")
public class UserController {

    private final UserProfileService userProfileService;
    private final AccountDeactivationService deactivationService;
    private final PasswordChangeService passwordChangeService;

    public UserController(UserProfileService userProfileService,
                          AccountDeactivationService deactivationService,
                          PasswordChangeService passwordChangeService) {
        this.userProfileService = userProfileService;
        this.deactivationService = deactivationService;
        this.passwordChangeService = passwordChangeService;
    }

    @GetMapping
    @Operation(summary = "내 계정+프로필 조회 (ACCT-01)")
    public ApiResponse<UserProfileResponse> getMe(@CurrentUser UUID userId) {
        return ApiResponse.ok(userProfileService.getMyProfile(userId));
    }

    @DeleteMapping
    @Operation(summary = "계정 비활성화 (ACCT-04·06)",
            description = "상태를 DEACTIVATED 로 두고 전 세션을 종료한다. 30일 복구창 안에는 "
                    + "POST /auth/reactivations 로 되살릴 수 있고, 이후 배치가 삭제한다(NFR-007). "
                    + "이미 비활성화된 계정은 복구창을 늘리지 않는다(멱등).")
    public ApiResponse<DeactivationResponse> deactivate(@CurrentUser UUID userId) {
        return ApiResponse.ok(deactivationService.deactivate(userId));
    }

    @PatchMapping("/profile")
    @Operation(summary = "프로필 수정 (ONB-02 · ACCT-01) — 부분 수정")
    public ApiResponse<UserProfileResponse> updateProfile(@CurrentUser UUID userId,
                                                          @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(userProfileService.updateProfile(userId, request));
    }

    @PatchMapping("/password")
    @Operation(summary = "비밀번호 변경 (ACCT-02)",
            description = "현재 비밀번호로 본인을 재확인한 뒤 교체하고, 열려 있던 세션을 전부 끊는다. "
                    + "현재 비밀번호가 다르거나 비밀번호가 없는 계정(소셜 가입)은 E-USER-001.")
    public ApiResponse<Void> changePassword(@CurrentUser UUID userId,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        passwordChangeService.changePassword(userId, request);
        return ApiResponse.ok(null);
    }
}
