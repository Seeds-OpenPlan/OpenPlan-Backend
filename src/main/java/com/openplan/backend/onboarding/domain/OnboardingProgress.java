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
