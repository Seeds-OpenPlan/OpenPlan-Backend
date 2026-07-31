package com.openplan.backend.landing.service;

import com.openplan.backend.landing.dto.LandingResponse;
import org.springframework.stereotype.Service;

/**
 * 랜딩 서비스(ST-B1-14 · LANDING) — 비인증 공개 랜딩 콘텐츠 조립.
 *
 * <p>랜딩 섹션 콘텐츠가 아직 설계에 없어(창작 금지 — AGENTS.md) 빈 섹션을 반환한다.
 * 콘텐츠가 확정되면 이 서비스가 시드/설정에서 섹션을 조립한다.
 */
@Service
public class LandingService {

    public LandingResponse getLanding() {
        return LandingResponse.empty();
    }
}
