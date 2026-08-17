package com.openplan.backend.global.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 메일 문구 카탈로그 검증 — {@code ErrorMessageCatalogTest}와 같은 역할, 같은 이유.
 *
 * <p>메일은 오류 문구보다 실패 비용이 크다. 오류 문구는 화면에 한 번 뜨고 사라지지만 메일은
 * 사용자 메일함에 <b>영구히 남고 회수할 수 없다.</b> 게다가 이 카탈로그는 실제 발송이 일어나야만
 * 읽히므로, 줄 이음(`\`)이나 치환 인자를 잘못 적으면 SMTP가 붙는 날까지 아무도 모른다.
 * 그 시점을 빌드로 당겨온다. Spring 컨텍스트(=DB)를 띄우지 않아 DB 없이도 돈다.
 */
class MailMessageCatalogTest {

    private static final String LINK = "https://example.test/verify?token=abc";
    private static final int HOURS = 24;

    /** P4 — 규칙 기반 제품이므로 문구가 AI를 암시하면 하지 않는 일을 말하게 된다(errors 카탈로그와 동일 규율). */
    private static final List<String> FORBIDDEN_P4_TERMS =
            List.of("AI", "인공지능", "머신러닝", "딥러닝", "학습", "자동 분석", "지능형");

    private final MailMessages mailMessages = new MailMessages();

    @Test
    @DisplayName("이메일 인증 문구가 해석되고 링크·유효 시간이 치환된다")
    void emailVerificationResolves() {
        String subject = mailMessages.resolve("mail.email-verification.subject");
        String body = mailMessages.resolve("mail.email-verification.body", LINK, HOURS);

        assertThat(subject).isNotBlank();
        assertThat(body).contains(LINK).contains(String.valueOf(HOURS));
        // 치환이 안 되면 자리표시자가 그대로 메일에 실린다
        assertThat(body).doesNotContain("{0}").doesNotContain("{1}");
    }

    @Test
    @DisplayName("비밀번호 재설정 문구가 해석되고 링크·유효 시간이 치환된다")
    void passwordResetResolves() {
        String subject = mailMessages.resolve("mail.password-reset.subject");
        String body = mailMessages.resolve("mail.password-reset.body", LINK, HOURS);

        assertThat(subject).isNotBlank();
        assertThat(body).contains(LINK).contains(String.valueOf(HOURS));
        assertThat(body).doesNotContain("{0}").doesNotContain("{1}");
    }

    @Test
    @DisplayName("본문이 여러 줄로 이어진다 — 줄 이음(\\) 오타면 한 줄로 뭉개진다")
    void bodyKeepsLineBreaks() {
        String body = mailMessages.resolve("mail.email-verification.body", LINK, HOURS);
        assertThat(body.lines().count()).isGreaterThan(3);
    }

    @Test
    @DisplayName("없는 키는 조용히 넘어가지 않는다 — 문구가 깨진 메일보다 안 나가는 편이 낫다")
    void missingKeyFailsLoudly() {
        assertThatThrownBy(() -> mailMessages.resolve("mail.does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail.properties");
    }

    @Test
    @DisplayName("P4 — 카탈로그에 AI 계열 표현이 없다")
    void catalogHasNoForbiddenTerms() throws IOException {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages/mail.properties")) {
            assertThat(in).as("messages/mail.properties 가 클래스패스에 있어야 한다").isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 주석 줄은 감사 대상이 아니다 — 사용자에게 나가지 않는다.
        String values = raw.lines()
                .filter(line -> !line.startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        for (String term : FORBIDDEN_P4_TERMS) {
            assertThat(values).as("P4 금지 표현: %s", term).doesNotContain(term);
        }
    }
}
