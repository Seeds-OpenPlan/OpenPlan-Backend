package com.openplan.backend.push.dto;

/**
 * VAPID 공개키 응답. {@code publicKey}가 null이면 서버에 VAPID 키가 설정되지 않았다는 뜻이고,
 * FE는 이것을 보고 푸시 설정 섹션을 숨긴다(ADR-0015 결정 ⑤ — 소셜 로그인 자격증명과 같은 패턴).
 */
public record VapidPublicKeyResponse(String publicKey) {
}
