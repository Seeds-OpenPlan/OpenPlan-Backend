package com.openplan.backend.support.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.support.domain.HelpArticle;
import com.openplan.backend.support.domain.HelpArticleStatus;
import com.openplan.backend.support.dto.HelpArticleResponse;
import com.openplan.backend.support.repository.HelpArticleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 도움말 서비스 단위 테스트(DB 불요). 빈 파라미터 정규화(→null)·매핑·상세 404를 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class HelpArticleServiceTest {

    @Mock
    private HelpArticleRepository repository;

    @InjectMocks
    private HelpArticleService service;

    @Test
    void search_빈_파라미터는_null로_정규화해_전달() {
        when(repository.search(null, null)).thenReturn(List.of(article("계정", "비밀번호 변경 방법")));

        List<HelpArticleResponse> res = service.search("", "   ");

        assertThat(res).hasSize(1);
        assertThat(res.get(0).title()).isEqualTo("비밀번호 변경 방법");
        verify(repository).search(null, null); // blank→null 확인
    }

    @Test
    void getArticle_PUBLISHED가_아니면_404() {
        UUID id = UUID.randomUUID();
        when(repository.findByHelpArticleIdAndStatus(id, HelpArticleStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getArticle(id))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }

    private static HelpArticle article(String category, String title) {
        HelpArticle a;
        try {
            var ctor = HelpArticle.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            a = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(a, "helpArticleId", UUID.randomUUID());
        ReflectionTestUtils.setField(a, "category", category);
        ReflectionTestUtils.setField(a, "title", title);
        ReflectionTestUtils.setField(a, "content", "본문");
        ReflectionTestUtils.setField(a, "status", HelpArticleStatus.PUBLISHED);
        ReflectionTestUtils.setField(a, "sortOrder", 0);
        return a;
    }
}
