package com.openplan.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 성공 응답 봉투 (ADR-0003 — {@code {data, meta}}).
 *
 * <p>목록 GET은 {@code meta.page}에 {@link PageMeta}를 담는다(api-contracts §1 페이지네이션).
 * 단건/무페이지 응답은 meta 생략(null → 직렬화 제외). 오류는 별도 봉투
 * {@link com.openplan.backend.global.error.ErrorResponse} 사용.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, Meta meta) {

    /**
     * 봉투 meta. {@code unreadCount}는 알림 센터 전용(정본 openapi {@code listNotifications})이라
     * 다른 응답에서는 null 이며, {@code NON_NULL}로 직렬화에서 빠진다 — 기존 응답의 모양은 변하지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(PageMeta page, Integer unreadCount) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> ok(T data, PageMeta page) {
        return new ApiResponse<>(data, new Meta(page, null));
    }

    /** 알림 센터 — 목록 + 페이지 + 미읽음 수(NOTI-02). */
    public static <T> ApiResponse<T> ok(T data, PageMeta page, int unreadCount) {
        return new ApiResponse<>(data, new Meta(page, unreadCount));
    }
}
