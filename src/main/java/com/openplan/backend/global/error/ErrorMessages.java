package com.openplan.backend.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 오류 문구 해석기 — {@code messages/errors.properties} 카탈로그의 유일한 진입점
 * (exceptions.md §1: "문구 원본은 서버 메시지 카탈로그 단일 파일").
 *
 * <p>{@link ErrorCode}는 코드·HTTP 상태만 갖고 문구를 갖지 않는다. 문구가 enum에도 있으면
 * P4(AI 표현 금지) grep 감사 대상이 두 곳으로 갈라져 "단일 지점"이 무너지기 때문이다.
 *
 * <p>키 누락은 조용히 넘기지 않는다 — 부팅 시 {@code ErrorMessageCatalogTest}가 전 코드의
 * 키 존재를 검증하고, 런타임에 그래도 빠진 경우는 ERROR 로그 + 일반 문구로 폴백한다
 * (사용자에게 키 문자열이 노출되는 사태만은 막는다).
 */
@Component
public class ErrorMessages {

    private static final Logger log = LoggerFactory.getLogger(ErrorMessages.class);

    /** 카탈로그 키 누락 시 최후 폴백 — E-COM-005와 동일 취지의 무해한 안내. */
    private static final String FALLBACK = "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    private final MessageSource messageSource;

    public ErrorMessages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages/errors");
        source.setDefaultEncoding("UTF-8");
        // 키를 그대로 반환하면 "E-COM-004"가 사용자 화면에 뜬다 — 폴백은 resolve()가 통제한다.
        source.setUseCodeAsDefaultMessage(false);
        this.messageSource = source;
    }

    /** 카탈로그 문구. 키가 없으면 ERROR 로그 후 일반 문구로 폴백한다. */
    public String resolve(ErrorCode errorCode) {
        try {
            return messageSource.getMessage(errorCode.code(), null, Locale.KOREAN);
        } catch (NoSuchMessageException ex) {
            log.error("오류 메시지 카탈로그에 키 없음: {} — messages/errors.properties 갱신 필요", errorCode.code());
            return FALLBACK;
        }
    }

    /** 키 존재 여부 (카탈로그 완전성 테스트용). */
    public boolean hasMessage(ErrorCode errorCode) {
        return messageSource.getMessage(errorCode.code(), null, null, Locale.KOREAN) != null;
    }
}
