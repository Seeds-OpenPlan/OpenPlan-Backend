package com.openplan.backend.landing.dto;

import java.util.List;

/**
 * 랜딩 응답 — openapi {@code /landing} 의 {@code data:{sections:[object]}}와 1:1.
 *
 * <p>섹션 항목의 구체 스키마는 계약상 자유 객체(array of object)다. 랜딩 문구/구성은 디자인 산출물에
 * 아직 정의되지 않아 서버가 창작하지 않는다(AGENTS.md). 현재는 빈 목록을 반환하며, 실제 섹션 콘텐츠 주입은
 * 후속(디자인 확정 + 시드/설정). 엔드포인트 계약(비인증 200)은 지금 성립한다.
 */
public record LandingResponse(List<Object> sections) {

    public static LandingResponse empty() {
        return new LandingResponse(List.of());
    }
}
