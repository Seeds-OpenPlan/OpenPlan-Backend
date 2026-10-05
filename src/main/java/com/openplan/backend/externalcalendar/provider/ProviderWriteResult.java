package com.openplan.backend.externalcalendar.provider;

/**
 * 쓰기가 끝난 뒤 제공자가 알려 준 참조 (#69). 호출부는 이 값을 매핑 행에 저장하고, 다음 수정·삭제
 * 때 {@link ExternalRef} 로 되돌려 쓴다.
 *
 * <p>ETag 를 반드시 받아 둔다 — 다음 쓰기의 {@code If-Match} 재료다. 못 받으면 그 일정은 다음에
 * 수정할 수 없고(모르면 쓰지 않는다), 다시 조회해 채워야 한다.
 */
public record ProviderWriteResult(String externalEventId, String resourceHref, String etag) {

    public ExternalRef toRef() {
        return new ExternalRef(externalEventId, resourceHref, etag);
    }
}
