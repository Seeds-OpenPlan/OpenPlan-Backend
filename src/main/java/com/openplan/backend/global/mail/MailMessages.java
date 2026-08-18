package com.openplan.backend.global.mail;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 메일 문구 해석기 — {@code messages/mail.properties} 카탈로그의 유일한 진입점.
 *
 * <p>{@link com.openplan.backend.global.error.ErrorMessages}와 같은 구조·같은 이유다:
 * 사용자에게 나가는 문구를 코드에 흩어 두면 <b>P4(AI 표현 금지) grep 감사의 단일 지점</b>이 무너진다.
 * 메일 본문도 사용자 노출 문구이므로 같은 규율을 받는다.
 *
 * <p>오류 카탈로그와 달리 <b>인자를 받는다</b> — 본문에 링크와 유효 시간이 들어가기 때문이다.
 * 치환은 {@code MessageFormat} 규칙을 따르므로 카탈로그에서 작은따옴표는 {@code ''}로 적어야 한다.
 *
 * <p>키가 없으면 <b>예외를 던진다.</b> 오류 문구는 폴백해서라도 응답을 내보내야 하지만, 메일은
 * 문구가 깨진 채 나가느니 안 나가는 편이 낫다 — 사용자에게 남는 흔적이 영구적이다.
 */
@Component
public class MailMessages {

    private final MessageSource messageSource;

    public MailMessages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages/mail");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        this.messageSource = source;
    }

    /**
     * @param key  카탈로그 키(예: {@code mail.email-verification.subject})
     * @param args {@code {0}}, {@code {1}} … 치환 인자
     * @throws IllegalStateException 키가 카탈로그에 없을 때
     */
    public String resolve(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, Locale.KOREAN);
        } catch (NoSuchMessageException e) {
            throw new IllegalStateException(
                    "메일 문구 카탈로그에 키 없음: " + key + " — messages/mail.properties 갱신 필요", e);
        }
    }
}
