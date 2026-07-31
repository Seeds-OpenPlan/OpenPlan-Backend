package com.openplan.backend.onboarding.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.onboarding.domain.OnboardingProgress;
import com.openplan.backend.onboarding.dto.OnboardingProgressResponse;
import com.openplan.backend.onboarding.dto.UpdateOnboardingProgressRequest;
import com.openplan.backend.onboarding.repository.OnboardingProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 온보딩 진행 서비스(ST-B1-08) — 진행 상태 조회 및 단계 플래그 부분 수정.
 *
 * <p>진행 행은 가입 시 생성되므로 여기서 신규 생성하지 않는다(없으면 무결성 위반 → E-COM-004).
 * TUT-09 재실행은 별도 연산이 아니라 이 PATCH로 플래그(tutorialDone 등)를 false로 되돌리는 것이다 —
 * 샘플 프로젝트 삭제는 FE가 BE-2 {@code DELETE /projects/{id}}로 수행하고, FK ON DELETE SET NULL이
 * tutorial_sample_project_id를 서버에서 null 처리한다(BE-1은 프로젝트를 지우지 않는다).
 */
@Service
public class OnboardingProgressService {

    private final OnboardingProgressRepository repository;

    public OnboardingProgressService(OnboardingProgressRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OnboardingProgressResponse getMyProgress(UUID userId) {
        return OnboardingProgressResponse.from(load(userId));
    }

    /** 제공된(non-null) 플래그만 수정하고 최신 상태를 반환한다(더티 체킹으로 UPDATE). */
    @Transactional
    public OnboardingProgressResponse updateProgress(UUID userId, UpdateOnboardingProgressRequest request) {
        OnboardingProgress progress = load(userId);

        if (request.introDone() != null) {
            progress.changeIntroDone(request.introDone());
        }
        if (request.profileDone() != null) {
            progress.changeProfileDone(request.profileDone());
        }
        if (request.availabilityDone() != null) {
            progress.changeAvailabilityDone(request.availabilityDone());
        }
        if (request.fixedScheduleDone() != null) {
            progress.changeFixedScheduleDone(request.fixedScheduleDone());
        }
        if (request.tutorialDone() != null) {
            progress.changeTutorialDone(request.tutorialDone());
        }
        if (request.calendarSyncDone() != null) {
            progress.changeCalendarSyncDone(request.calendarSyncDone());
        }

        return OnboardingProgressResponse.from(progress);
    }

    private OnboardingProgress load(UUID userId) {
        return repository.findById(userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
    }
}
