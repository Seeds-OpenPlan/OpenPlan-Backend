package com.openplan.backend.announcement.service;

import com.openplan.backend.announcement.domain.Announcement;
import com.openplan.backend.announcement.domain.AnnouncementStatus;
import com.openplan.backend.announcement.domain.AnnouncementType;
import com.openplan.backend.announcement.dto.AnnouncementResponse;
import com.openplan.backend.announcement.repository.AnnouncementRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공지 서비스 단위 테스트(DB 불요). 목록은 PUBLISHED만·유형 매핑, 상세 미게시는 404를 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    private AnnouncementRepository repository;

    @InjectMocks
    private AnnouncementService service;

    @Test
    void getPublished_목록은_PUBLISHED만_조회하고_유형을_매핑() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByStatusOrderByPublishedStartAtDesc(eq(AnnouncementStatus.PUBLISHED), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(announcement(AnnouncementType.INCIDENT, "장애 안내")), pageable, 1));

        Page<AnnouncementResponse> res = service.getPublished(pageable);

        assertThat(res.getContent()).hasSize(1);
        assertThat(res.getContent().get(0).announcementType()).isEqualTo(AnnouncementType.INCIDENT);
        assertThat(res.getContent().get(0).title()).isEqualTo("장애 안내");
        verify(repository).findByStatusOrderByPublishedStartAtDesc(AnnouncementStatus.PUBLISHED, pageable);
    }

    @Test
    void getPublished_상세_미게시_또는_미존재는_404() {
        UUID id = UUID.randomUUID();
        when(repository.findByAnnouncementIdAndStatus(id, AnnouncementStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublished(id))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }

    @Test
    void getPublished_상세_매핑() {
        UUID id = UUID.randomUUID();
        Announcement a = announcement(AnnouncementType.CHANGE, "변경 안내");
        when(repository.findByAnnouncementIdAndStatus(id, AnnouncementStatus.PUBLISHED)).thenReturn(Optional.of(a));

        AnnouncementResponse res = service.getPublished(id);

        assertThat(res.announcementType()).isEqualTo(AnnouncementType.CHANGE);
        assertThat(res.title()).isEqualTo("변경 안내");
    }

    private static Announcement announcement(AnnouncementType type, String title) {
        Announcement a;
        try {
            var ctor = Announcement.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            a = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(a, "announcementId", UUID.randomUUID());
        ReflectionTestUtils.setField(a, "announcementType", type);
        ReflectionTestUtils.setField(a, "title", title);
        ReflectionTestUtils.setField(a, "content", "본문");
        ReflectionTestUtils.setField(a, "publishedStartAt", Instant.parse("2026-07-21T00:00:00Z"));
        ReflectionTestUtils.setField(a, "status", AnnouncementStatus.PUBLISHED);
        return a;
    }
}
