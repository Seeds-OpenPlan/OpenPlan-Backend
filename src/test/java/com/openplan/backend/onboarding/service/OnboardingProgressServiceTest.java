package com.openplan.backend.onboarding.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.onboarding.domain.OnboardingProgress;
import com.openplan.backend.onboarding.dto.OnboardingProgressResponse;
import com.openplan.backend.onboarding.dto.UpdateOnboardingProgressRequest;
import com.openplan.backend.onboarding.repository.OnboardingProgressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 온보딩 진행 서비스 단위 테스트(DB 불요 — 리포지토리 목킹). 조회·부분 수정·미존재를 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingProgressServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private OnboardingProgressRepository repository;

    @InjectMocks
    private OnboardingProgressService service;

    @Test
    void getMyProgress_진행상태를_반환한다() {
        OnboardingProgress progress = progress(true, true, false, false, false, false, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(progress));

        OnboardingProgressResponse res = service.getMyProgress(USER_ID);

        assertThat(res.introDone()).isTrue();
        assertThat(res.profileDone()).isTrue();
        assertThat(res.availabilityDone()).isFalse();
        assertThat(res.tutorialSampleProjectId()).isNull();
    }

    @Test
    void getMyProgress_행이_없으면_E_COM_004() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProgress(USER_ID))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }

    @Test
    void updateProgress_제공된_플래그만_수정한다() {
        OnboardingProgress progress = progress(false, false, false, false, false, false, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(progress));

        // introDone·profileDone만 true로, 나머지는 미제공(null) → 불변
        OnboardingProgressResponse res = service.updateProgress(USER_ID,
                new UpdateOnboardingProgressRequest(true, true, null, null, null, null));

        assertThat(progress.isIntroDone()).isTrue();
        assertThat(progress.isProfileDone()).isTrue();
        assertThat(progress.isAvailabilityDone()).isFalse();
        assertThat(progress.isTutorialDone()).isFalse();
        assertThat(res.introDone()).isTrue();
    }

    @Test
    void updateProgress_TUT09_튜토리얼_리셋은_tutorialDone을_false로() {
        OnboardingProgress progress = progress(true, true, true, true, true, true, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(progress));

        service.updateProgress(USER_ID,
                new UpdateOnboardingProgressRequest(null, null, null, null, false, null));

        assertThat(progress.isTutorialDone()).isFalse();
        assertThat(progress.isIntroDone()).isTrue(); // 다른 플래그는 불변
    }

    @Test
    void updateProgress_모든_플래그가_null이면_변경없음() {
        OnboardingProgress progress = progress(true, false, true, false, true, false, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(progress));

        service.updateProgress(USER_ID,
                new UpdateOnboardingProgressRequest(null, null, null, null, null, null));

        assertThat(progress.isIntroDone()).isTrue();
        assertThat(progress.isProfileDone()).isFalse();
        assertThat(progress.isAvailabilityDone()).isTrue();
    }

    private static OnboardingProgress progress(boolean intro, boolean profile, boolean availability,
                                               boolean fixed, boolean tutorial, boolean calendar,
                                               UUID sampleProjectId) {
        OnboardingProgress p = instantiate(OnboardingProgress.class);
        ReflectionTestUtils.setField(p, "userId", USER_ID);
        ReflectionTestUtils.setField(p, "introDone", intro);
        ReflectionTestUtils.setField(p, "profileDone", profile);
        ReflectionTestUtils.setField(p, "availabilityDone", availability);
        ReflectionTestUtils.setField(p, "fixedScheduleDone", fixed);
        ReflectionTestUtils.setField(p, "tutorialDone", tutorial);
        ReflectionTestUtils.setField(p, "calendarSyncDone", calendar);
        ReflectionTestUtils.setField(p, "tutorialSampleProjectId", sampleProjectId);
        return p;
    }

    /** 엔티티의 protected 무인자 생성자(JPA 전용)를 테스트에서 호출하기 위한 리플렉션 인스턴스화. */
    private static <T> T instantiate(Class<T> type) {
        try {
            java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 픽스처 생성 실패: " + type.getSimpleName(), e);
        }
    }
}
