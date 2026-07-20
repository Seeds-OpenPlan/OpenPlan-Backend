package com.openplan.backend.onboarding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openplan.backend.onboarding.domain.OnboardingStep;
import com.openplan.backend.onboarding.dto.OnboardingContentItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 온보딩 콘텐츠 로더 테스트 — 실제 리소스({@code onboarding/contents.json})를 로드해 검증(DB 불요).
 * step별 카드 수·문구 정합 + P4(AI 표현 금지) 준수를 확인한다.
 */
class OnboardingContentServiceTest {

    private final OnboardingContentService service = new OnboardingContentService(new ObjectMapper());

    @Test
    void INTRO는_소개_2장을_순서대로_반환한다() {
        List<OnboardingContentItem> intro = service.getContents(OnboardingStep.INTRO);

        assertThat(intro).hasSize(2);
        assertThat(intro.get(0).order()).isEqualTo(1);
        assertThat(intro.get(0).title()).contains("계획 검증");
        assertThat(intro.get(1).title()).contains("일정 관리");
    }

    @Test
    void 가용_고정_안내는_각_1장() {
        assertThat(service.getContents(OnboardingStep.AVAILABILITY_GUIDE)).hasSize(1);
        assertThat(service.getContents(OnboardingStep.FIXED_SCHEDULE_GUIDE)).hasSize(1);
    }

    @Test
    void TUTORIAL은_정적_콘텐츠가_없어_빈_목록() {
        assertThat(service.getContents(OnboardingStep.TUTORIAL)).isEmpty();
    }

    @Test
    void P4_전_콘텐츠에_금지_명칭_AI_문자열이_없다() {
        for (OnboardingStep step : OnboardingStep.values()) {
            for (OnboardingContentItem item : service.getContents(step)) {
                assertThat(item.title()).doesNotContain("AI");
                assertThat(item.body()).doesNotContain("AI");
            }
        }
    }
}
