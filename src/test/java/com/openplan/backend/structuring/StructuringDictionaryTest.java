package com.openplan.backend.structuring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openplan.backend.structuring.service.StructuringDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 사전 골든 (SS-03) — 정적 데이터라 <b>같은 입력이면 언제나 같은 출력</b>이어야 한다(C-1).
 * 그 성질이 깨지면 사용자에게 근거(C-3)를 설명할 수 없다.
 */
class StructuringDictionaryTest {

    private final StructuringDictionary dictionary = new StructuringDictionary(new ObjectMapper());

    @Test
    @DisplayName("이름에 키워드가 있으면 그 유형이 걸린다")
    void 키워드_매칭() {
        assertThat(dictionary.match("졸업 논문 쓰기").key()).isEqualTo("PAPER");
        assertThat(dictionary.match("중간고사 대비").key()).isEqualTo("EXAM");
        assertThat(dictionary.match("웹 개발 프로젝트").key()).isIn("DEV");
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다 — 'PT' 같은 영문 키워드가 있다")
    void 대소문자_무시() {
        assertThat(dictionary.match("최종 pt 준비").key()).isEqualTo("PRESENTATION");
    }

    @Test
    @DisplayName("어디에도 안 걸리면 GENERIC 으로 떨어진다 — 빈 목록을 주지 않는다")
    void 폴백() {
        StructuringDictionary.Match m = dictionary.match("zzz 알 수 없는 무엇");

        assertThat(m.key()).isEqualTo("GENERIC");
        assertThat(m.drafts()).isNotEmpty();
        assertThat(m.keyword()).isNull();
    }

    @Test
    @DisplayName("같은 이름이면 언제나 같은 결과 (C-1 결정성)")
    void 결정적이다() {
        var a = dictionary.match("기말고사 공부");
        var b = dictionary.match("기말고사 공부");

        assertThat(a.key()).isEqualTo(b.key());
        assertThat(a.drafts()).usingRecursiveComparison().isEqualTo(b.drafts());
    }

    @Test
    @DisplayName("근거 문구에 'AI' 표현을 쓰지 않는다 (P4)")
    void 근거는_규칙_기반_문구다() {
        assertThat(dictionary.match("보고서 작성").reason())
                .contains("보고서").doesNotContainIgnoringCase("AI").doesNotContain("추천");
        assertThat(dictionary.match("무엇이든").reason()).doesNotContainIgnoringCase("AI");
    }

    @Test
    @DisplayName("사전의 예상시간은 DB CHECK(5분 단위·양수)를 이미 지킨다")
    void 사전값이_제약을_지킨다() {
        for (String name : new String[]{"보고서", "시험", "발표", "논문", "개발", "공부", "기타"}) {
            assertThat(dictionary.match(name).drafts()).allSatisfy(d -> {
                assertThat(d.estimatedMinutes()).isNotNull().isPositive();
                assertThat(d.estimatedMinutes() % 5).isZero();
                assertThat(d.priority()).isBetween(1, 3);
            });
        }
    }
}
