package com.openplan.backend.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 온보딩·튜토리얼 진행 상태 엔티티 — {@code onboarding_progress}(users와 1:1, user_id가 PK).
 * 단계별 완료 플래그를 보유해 이탈 후 이어하기(SQ-07)와 튜토리얼 재실행(TUT-09) 리셋을 지원한다.
 *
 * <p>{@code tutorialSampleProjectId}(B13)는 서버 관리값이다 — 튜토리얼 샘플 생성(TUT-03) 시 기록되고,
 * 샘플 프로젝트 삭제 시 FK {@code ON DELETE SET NULL}로 서버가 null 처리한다. 클라이언트 PATCH로는
 * 변경하지 않는다(읽기 전용 노출). 07은 신규 행 생성 없이(가입 스토리 소관) 기존 행을 읽고 플래그만 수정한다.
 */
@Entity
@Table(name = "onboarding_progress")
public class OnboardingProgress {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "intro_done", nullable = false)
    private boolean introDone;

    @Column(name = "profile_done", nullable = false)
    private boolean profileDone;

    @Column(name = "availability_done", nullable = false)
    private boolean availabilityDone;

    @Column(name = "fixed_schedule_done", nullable = false)
    private boolean fixedScheduleDone;

    @Column(name = "tutorial_done", nullable = false)
    private boolean tutorialDone;

    @Column(name = "calendar_sync_done", nullable = false)
    private boolean calendarSyncDone;

    /** 잔존 튜토리얼 샘플 프로젝트 id(non-null=잔존). 서버 관리 — 클라이언트가 PATCH로 못 바꾼다. */
    @Column(name = "tutorial_sample_project_id")
    private UUID tutorialSampleProjectId;

    /** JPA 전용 기본 생성자. */
    protected OnboardingProgress() {
    }

    /**
     * 가입 시 진행 상태 행 생성(ST-B1-04) — 전 단계 미완료로 시작한다.
     *
     * <p>{@link com.openplan.backend.onboarding.service.OnboardingProgressService}는 행이 없으면
     * E-COM-004를 던지고 생성 엔드포인트는 계약에 없다. 프로필과 같은 이유로 가입이 만들어 둔다.
     */
    public static OnboardingProgress createInitial(UUID userId) {
        OnboardingProgress p = new OnboardingProgress();
        p.userId = userId;
        p.introDone = false;
        p.profileDone = false;
        p.availabilityDone = false;
        p.fixedScheduleDone = false;
        p.tutorialDone = false;
        p.calendarSyncDone = false;
        p.tutorialSampleProjectId = null;
        return p;
    }

    /**
     * 온보딩 완료 여부 — {@code SessionInfo.onboardingCompleted}(openapi)의 원천.
     *
     * <p>🔴 <b>"완료"의 정의가 명세에 없다.</b> 여기서는 <b>소비자 쪽 정의를 따랐다</b> —
     * 프론트 {@code onboardingApi.js}의 {@code WIZARD_ORDER}가 마법사 단계를
     * {@code profileDone → availabilityDone → fixedScheduleDone → calendarSyncDone} 넷으로 잡고
     * 그것으로 커서를 파생한다(2026-07-29 실서버 대조 주석). 서버가 다른 기준을 쓰면 같은 계정이
     * 화면마다 다르게 판정된다.
     *
     * <p>{@code introDone}(소개 2장)과 {@code tutorialDone}(코치마크)은 제외한다 — 마법사 단계가 아니고,
     * 튜토리얼은 완료 후 재실행이 가능해(TUT-09) 완료 판정에 넣으면 되돌아간다.
     * 캘린더 연동은 건너뛰기가 이 플래그를 세우므로(PATCH "단계 완료/건너뛰기") 미연동 사용자가 갇히지 않는다.
     *
     * <p>정의를 바꿀 거라면 프론트 {@code WIZARD_ORDER}와 함께 바꿔야 한다 — 팀 확인 대상.
     */
    public boolean isOnboardingCompleted() {
        return profileDone && availabilityDone && fixedScheduleDone && calendarSyncDone;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isIntroDone() {
        return introDone;
    }

    public boolean isProfileDone() {
        return profileDone;
    }

    public boolean isAvailabilityDone() {
        return availabilityDone;
    }

    public boolean isFixedScheduleDone() {
        return fixedScheduleDone;
    }

    public boolean isTutorialDone() {
        return tutorialDone;
    }

    public boolean isCalendarSyncDone() {
        return calendarSyncDone;
    }

    public UUID getTutorialSampleProjectId() {
        return tutorialSampleProjectId;
    }

    // ---- 단계 플래그 수정 (PATCH — 제공된 플래그만 서비스가 선택 호출) ----

    public void changeIntroDone(boolean done) {
        this.introDone = done;
    }

    public void changeProfileDone(boolean done) {
        this.profileDone = done;
    }

    public void changeAvailabilityDone(boolean done) {
        this.availabilityDone = done;
    }

    public void changeFixedScheduleDone(boolean done) {
        this.fixedScheduleDone = done;
    }

    public void changeTutorialDone(boolean done) {
        this.tutorialDone = done;
    }

    public void changeCalendarSyncDone(boolean done) {
        this.calendarSyncDone = done;
    }
}
