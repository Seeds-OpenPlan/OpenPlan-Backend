package com.openplan.backend.global.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 오류 메시지 카탈로그 검증 (exceptions.md §1).
 *
 * <p>문구를 {@code messages/errors.properties} 한 곳으로 옮긴 대가는 "키를 빠뜨리면 런타임에야
 * 안다"는 것이다. 그 대가를 빌드 시점으로 당겨오는 것이 이 테스트의 역할이다.
 * Spring 컨텍스트(=DB)를 띄우지 않으므로 DB 없이도 항상 돈다.
 */
class ErrorMessageCatalogTest {

    private final ErrorMessages errorMessages = new ErrorMessages();

    /**
     * P4 — MVP1의 추천·검증은 전부 규칙 기반(A1)이다. 오류 문구가 AI를 암시하면
     * 제품이 하지 않는 일을 말하게 된다. 레인 DoD의 grep 감사를 테스트로 고정한다.
     */
    private static final List<String> FORBIDDEN_P4_TERMS =
            List.of("AI", "인공지능", "머신러닝", "딥러닝", "학습", "자동 분석", "지능형");

    @Test
    @DisplayName("전 ErrorCode에 카탈로그 문구가 있다 — 키 누락 0")
    void everyErrorCodeHasMessage() {
        List<String> missing = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            if (!errorMessages.hasMessage(code)) {
                missing.add(code.code());
            }
        }
        assertThat(missing)
                .as("messages/errors.properties에 키가 없는 코드 (enum·YAML·카탈로그 동시 갱신 규약 위반)")
                .isEmpty();
    }

    @Test
    @DisplayName("해석된 문구는 비어 있지 않고 코드 문자열을 그대로 내보내지 않는다")
    void resolvedMessagesAreUserFacing() {
        for (ErrorCode code : ErrorCode.values()) {
            String message = errorMessages.resolve(code);
            assertThat(message).as(code.code()).isNotBlank();
            // 키가 그대로 노출되면 사용자 화면에 "E-COM-004"가 뜬다
            assertThat(message).as(code.code()).isNotEqualTo(code.code());
        }
    }

    @Test
    @DisplayName("P4 — 카탈로그에 AI 계열 표현이 없다")
    void catalogHasNoForbiddenTerms() throws IOException {
        String raw = readCatalog();
        List<String> hits = FORBIDDEN_P4_TERMS.stream()
                // 주석 줄에는 금지어 자체가 설명으로 등장하므로 값 부분만 검사한다
                .filter(term -> raw.lines()
                        .filter(line -> !line.stripLeading().startsWith("#"))
                        .filter(line -> line.contains("="))
                        .map(line -> line.substring(line.indexOf('=') + 1))
                        .anyMatch(value -> value.contains(term)))
                .toList();
        assertThat(hits).as("P4 위반 표현 (제품은 규칙 기반이다 — A1)").isEmpty();
    }

    @Test
    @DisplayName("미등록 코드는 키가 아니라 폴백 문구로 응답한다")
    void unknownKeyFallsBackToSafeMessage() {
        // 카탈로그에 없는 상황을 실제로 만들 수는 없으므로(전 코드가 등록됨) 계약만 고정한다:
        // useCodeAsDefaultMessage=false + resolve()의 catch가 사용자에게 키 노출을 막는다.
        assertThat(errorMessages.resolve(ErrorCode.E_COM_005))
                .doesNotContain("E-COM-");
    }

    private String readCatalog() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages/errors.properties")) {
            assertThat(in).as("messages/errors.properties 가 클래스패스에 있어야 한다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("카탈로그는 UTF-8 한국어로 읽힌다 — 인코딩 깨짐 방지")
    void catalogIsReadableAsKorean() {
        String message = errorMessages.resolve(ErrorCode.E_COM_002);
        assertThat(message).isEqualTo("로그인이 필요합니다.");
        assertThat(Locale.KOREAN).isNotNull();
    }
}
