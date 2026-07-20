package com.openplan.backend.user.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.user.dto.UpdateProfileRequest;
import com.openplan.backend.user.dto.UserProfileResponse;
import com.openplan.backend.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 내 정보·프로필 컨트롤러(ST-B1-07 · ACCT-01 · ONB-02).
 *
 * <p>경로는 접두 없이 매핑하고 프레임워크가 {@code /api/v1}을 부여한다(AuthStubController와 동일 관례).
 * 인증 주체는 {@code @CurrentUser}로만 받는다(D-16 — 스텁/JWT 무관). DELETE /users/me(비활성화, ACCT-04)와
 * PATCH /users/me/password(ACCT-02)는 각각 ST-B1-06·05 소관으로 이 컨트롤러에 포함하지 않는다.
 */
@RestController
@RequestMapping("/users/me")
@Tag(name = "user", description = "내 정보·프로필 (ACCT-01 · ONB-02)")
public class UserController {

    private final UserProfileService userProfileService;

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    @Operation(summary = "내 계정+프로필 조회 (ACCT-01)")
    public ApiResponse<UserProfileResponse> getMe(@CurrentUser UUID userId) {
        return ApiResponse.ok(userProfileService.getMyProfile(userId));
    }

    @PatchMapping("/profile")
    @Operation(summary = "프로필 수정 (ONB-02 · ACCT-01) — 부분 수정")
    public ApiResponse<UserProfileResponse> updateProfile(@CurrentUser UUID userId,
                                                          @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(userProfileService.updateProfile(userId, request));
    }
}
