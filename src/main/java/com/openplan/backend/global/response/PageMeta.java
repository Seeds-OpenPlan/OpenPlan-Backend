package com.openplan.backend.global.response;

import org.springframework.data.domain.Page;

/**
 * 페이지네이션 메타 (api-contracts §1 — {@code meta.page:{number,size,totalElements,totalPages}}).
 * 요청은 {@code ?page=1&size=20} (1-base) — Spring Data는 0-base이므로 number는 +1 하여 노출.
 */
public record PageMeta(int number, int size, long totalElements, int totalPages) {

    public static PageMeta from(Page<?> page) {
        return new PageMeta(
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
