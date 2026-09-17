package com.openplan.backend.externalcalendar.provider;

/**
 * 이미 밖에 있는 일정을 가리키는 참조 — 수정·삭제의 대상 (#69).
 *
 * <p>제공자마다 주소를 만드는 재료가 다르다.
 * <ul>
 *   <li>구글 — {@code externalEventId}(이벤트 id). 캘린더 id 와 합쳐 주소가 된다.</li>
 *   <li>애플 — {@code resourceHref}({@code .ics} 리소스 주소). 그 자체가 주소다.</li>
 * </ul>
 *
 * @param etag 🔴 <b>없으면 수정·삭제를 하지 않는다.</b> {@code If-Match} 없이 보내면 그 사이 남이
 *             고친 것을 <b>말없이 덮는다.</b> 우리가 만든 일정은 생성 응답에서 ETag 를 받으므로,
 *             null 이라는 것은 그 값을 잃었다는 뜻이고 그때는 모르는 것이다 — 모르면 쓰지 않는다.
 */
public record ExternalRef(String externalEventId, String resourceHref, String etag) {

    public boolean hasEtag() {
        return etag != null && !etag.isBlank();
    }
}
