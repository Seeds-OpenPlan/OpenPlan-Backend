package com.openplan.backend.onboarding.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openplan.backend.onboarding.domain.OnboardingStep;
import com.openplan.backend.onboarding.dto.OnboardingContentItem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 온보딩 콘텐츠 제공 — 서버 시드 문구를 {@code resources/onboarding/contents.json}에서 1회 로드해
 * 메모리에 보관한다(문구는 고정, DB 테이블 없음). 문구 원본을 단일 파일로 외부화해 P4(AI 표현 금지)
 * grep 감사의 단일 지점을 유지한다 — 코드에 문구를 하드코딩하지 않는다.
 *
 * <p>step에 매핑이 없으면 빈 목록을 반환한다(TUTORIAL은 실 UI 코치마크라 정적 카드가 없어 빈 목록).
 */
@Service
public class OnboardingContentService {

    private final Map<OnboardingStep, List<OnboardingContentItem>> contentsByStep;

    public OnboardingContentService(ObjectMapper objectMapper) {
        this.contentsByStep = load(objectMapper);
    }

    public List<OnboardingContentItem> getContents(OnboardingStep step) {
        return contentsByStep.getOrDefault(step, List.of());
    }

    private Map<OnboardingStep, List<OnboardingContentItem>> load(ObjectMapper objectMapper) {
        try (InputStream is = new ClassPathResource("onboarding/contents.json").getInputStream()) {
            Map<String, List<OnboardingContentItem>> raw =
                    objectMapper.readValue(is, new TypeReference<>() {
                    });
            // 문자열 키를 enum으로 변환하며 검증(오탈자·미정의 step은 부팅 시점에 즉시 실패시킨다).
            Map<OnboardingStep, List<OnboardingContentItem>> result = new EnumMap<>(OnboardingStep.class);
            raw.forEach((key, value) -> result.put(OnboardingStep.valueOf(key), value));
            return result;
        } catch (IOException e) {
            // 콘텐츠 리소스는 필수 산출물 — 없으면 부팅을 세운다(조용한 빈 응답보다 낫다).
            throw new IllegalStateException("온보딩 콘텐츠 리소스 로드 실패: onboarding/contents.json", e);
        }
    }
}
