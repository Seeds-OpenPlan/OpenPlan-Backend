package com.openplan.backend.structuring.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 구조화 규칙 사전 (SS-03) — {@code structuring-dictionary-v1.json}.
 *
 * <p>🔴 <b>정적 데이터다. LLM 이 아니다</b>(C-1 · service-stories.md §62: "규칙 사전은 정적 데이터여야
 * 한다"). 같은 프로젝트명이면 언제나 같은 초안이 나오고, 그래서 사용자에게 "왜 이게 나왔나" 를
 * 설명할 수 있다.
 *
 * <p>매칭은 <b>프로젝트명에 키워드가 들어 있는가</b> 하나뿐이다. 형태소 분석도 유사도도 쓰지 않는다 —
 * 규칙이 단순해야 근거 문구(C-3)가 사실과 어긋나지 않는다. 어디에도 안 걸리면 {@code GENERIC} 으로
 * 떨어진다(빈 목록을 돌려주면 화면이 "제안 없음" 을 띄우는 것 말고 할 일이 없다).
 *
 * <p>기동 시 한 번 읽는다. 파일이 없거나 깨졌으면 <b>기동을 실패시킨다</b> — 사전 없이 뜬 서버는
 * 이 기능만 조용히 빈 목록을 돌려주게 되고, 그건 오류보다 찾기 어렵다.
 */
@Component
public class StructuringDictionary {

    private static final String PATH = "structuring-dictionary-v1.json";
    private static final String FALLBACK_KEY = "GENERIC";

    private final List<Entry> entries;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dictionary(int version, List<Entry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(String key, List<String> keywords, List<Draft> drafts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Draft(String title, Integer estimatedMinutes, Integer priority) {
    }

    public StructuringDictionary(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            Dictionary d = objectMapper.readValue(in, Dictionary.class);
            if (d.entries() == null || d.entries().isEmpty()) {
                throw new IllegalStateException(PATH + " 에 entries 가 없다");
            }
            this.entries = List.copyOf(d.entries());
        } catch (IOException e) {
            throw new IllegalStateException(PATH + " 을 읽지 못했다 — 구조화 초안을 만들 수 없다", e);
        }
    }

    /**
     * 프로젝트명 → 초안 목록. 앞선 항목이 먼저 매칭된다(사전 순서가 우선순위다).
     *
     * @return 매칭 결과. 어디에도 안 걸리면 GENERIC.
     */
    public Match match(String projectName) {
        String name = projectName == null ? "" : projectName.toLowerCase(Locale.ROOT);
        for (Entry e : entries) {
            if (e.keywords() == null) {
                continue;
            }
            for (String kw : e.keywords()) {
                if (!kw.isBlank() && name.contains(kw.toLowerCase(Locale.ROOT))) {
                    return new Match(e.key(), kw, e.drafts());
                }
            }
        }
        return entries.stream()
                .filter(e -> FALLBACK_KEY.equals(e.key()))
                .findFirst()
                .map(e -> new Match(e.key(), null, e.drafts()))
                .orElseThrow(() -> new IllegalStateException(PATH + " 에 " + FALLBACK_KEY + " 항목이 없다"));
    }

    /**
     * @param key         매칭된 사전 항목
     * @param keyword     걸린 키워드. GENERIC 폴백이면 null
     * @param drafts      그 항목의 초안 목록
     */
    public record Match(String key, String keyword, List<Draft> drafts) {

        /** 사용자에게 보일 근거 문구 (C-3) — 규칙 기반이라 "AI가 추천" 류 표현을 쓰지 않는다(P4). */
        public String reason() {
            return keyword == null
                    ? "이름에서 유형을 찾지 못해 기본 구성을 제안했습니다"
                    : "프로젝트 이름의 '" + keyword + "'(으)로 " + key + " 유형 구성을 제안했습니다";
        }
    }
}
