package com.openplan.backend.task.service.port;

import java.util.UUID;

/**
 * 카테고리 소유 존재 판정 (TB-6 · D-8). ST-B2-04(카테고리 도메인) 이관 계약 — 인터페이스 불변.
 *
 * <p>생성/편집에서 categoryId가 제공되면 이 판정으로 참조 무결성을 확인한다. false = 404 E-COM-004
 * (부재/타인 소유 구분 불가 은닉). <b>침묵 null 치환 금지</b>(D-8) — 없는 카테고리를 조용히 무시하지 않는다.
 */
public interface TaskCategoryChecker {

    /** 본인 소유 카테고리 존재 여부. false면 서비스가 404 E-COM-004로 라우팅한다. */
    boolean existsOwned(UUID categoryId, UUID userId);
}
