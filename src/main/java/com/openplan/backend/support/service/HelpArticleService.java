package com.openplan.backend.support.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.support.domain.HelpArticleStatus;
import com.openplan.backend.support.dto.HelpArticleResponse;
import com.openplan.backend.support.repository.HelpArticleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 도움말 서비스(ST-B1-13 · HELP-06) — 공개 콘텐츠 검색·상세. PUBLISHED만 노출.
 * 검색 0건은 빈 목록(200)이다(AC4). 현재 help_articles에 시드가 없어 실제 결과는 비어 있다(콘텐츠 후속).
 *
 * <p>조회 전용 서비스라 트랜잭션은 클래스 레벨 {@code readOnly}가 기본이다.
 */
@Service
@Transactional(readOnly = true)
public class HelpArticleService {

    private final HelpArticleRepository repository;

    public HelpArticleService(HelpArticleRepository repository) {
        this.repository = repository;
    }

    public List<HelpArticleResponse> search(String keyword, String category) {
        return repository.search(blankToNull(keyword), blankToNull(category)).stream()
                .map(HelpArticleResponse::from)
                .toList();
    }

    public HelpArticleResponse getArticle(UUID articleId) {
        return repository.findByHelpArticleIdAndStatus(articleId, HelpArticleStatus.PUBLISHED)
                .map(HelpArticleResponse::from)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
    }

    /** 빈 문자열 파라미터는 "필터 없음"(null)으로 정규화 — 쿼리의 :param is null 분기와 맞춘다. */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
