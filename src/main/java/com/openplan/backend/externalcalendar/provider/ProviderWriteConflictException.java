package com.openplan.backend.externalcalendar.provider;

/**
 * 그 사이 남이 고쳤다 — {@code If-Match} 가 어긋나 제공자가 412 를 준 경우 (#69).
 *
 * <p><b>실패가 아니라 정보다.</b> 우리가 덮지 않은 것이 옳게 동작한 것이고, 호출부가 할 일은
 * 재시도가 아니라 <b>다시 읽어 와 최신 상태를 확인하는 것</b>이다. 그냥 다시 보내면 방금 막은
 * 덮어쓰기를 손으로 하는 셈이 된다.
 */
public class ProviderWriteConflictException extends RuntimeException {

    public ProviderWriteConflictException(String message) {
        super(message);
    }
}
