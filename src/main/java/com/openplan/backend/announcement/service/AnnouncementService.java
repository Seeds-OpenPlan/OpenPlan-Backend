package com.openplan.backend.announcement.service;

import com.openplan.backend.announcement.domain.AnnouncementStatus;
import com.openplan.backend.announcement.dto.AnnouncementResponse;
import com.openplan.backend.announcement.repository.AnnouncementRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 공지 서비스(ST-B1-14 · OPS-01/02) — 공개 콘텐츠 목록·상세. PUBLISHED만 노출한다.
 *
 * <p>목록은 게시일 DESC(AC①). 게시 종료/숨김(ENDED/HIDDEN)은 상태로 관리하며(신규 스케줄러 금지 — ADR-0006),
 * 노출 여부는 status로 결정한다. 상세에서 미게시/미존재는 404 E-COM-004로 동일 처리(존재 은닉).
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository repository;

    public AnnouncementService(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<AnnouncementResponse> getPublished(Pageable pageable) {
        return repository.findByStatusOrderByPublishedStartAtDesc(AnnouncementStatus.PUBLISHED, pageable)
                .map(AnnouncementResponse::from);
    }

    @Transactional(readOnly = true)
    public AnnouncementResponse getPublished(UUID announcementId) {
        return repository.findByAnnouncementIdAndStatus(announcementId, AnnouncementStatus.PUBLISHED)
                .map(AnnouncementResponse::from)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
    }
}
