package com.openplan.backend.category.service;

import com.openplan.backend.category.domain.TaskCategory;
import com.openplan.backend.category.dto.TaskCategoryCreateRequest;
import com.openplan.backend.category.dto.TaskCategoryResponse;
import com.openplan.backend.category.repository.TaskCategoryRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 카테고리 유스케이스 파사드 (ST-B2-04 / SC-01). 검증은 {@link TaskCategoryValidator}, 시각은 {@link UserClock}.
 *
 * <p>본 슬라이스는 생성만 구현한다. 목록(L)·삭제(D)·port 이관은 후속 슬라이스에서 추가한다.
 */
@Service
public class TaskCategoryService {

    private final TaskCategoryRepository repository;
    private final TaskCategoryValidator validator;
    private final UserClock clock;

    public TaskCategoryService(TaskCategoryRepository repository, TaskCategoryValidator validator, UserClock clock) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * 카테고리 생성 (SC-01 / AC①). name 검증(422) → 이름 중복 판정(409) → 저장.
     *
     * <p>중복은 사전 조회로 409 E-CAT-001을 낸다. DB {@code UNIQUE(user_id, name)}가 경합 백스톱이다.
     * sortOrder는 재정렬 엔드포인트가 없어 0으로 생성(목록은 sort_order ASC, name ASC).
     */
    @Transactional
    public TaskCategoryResponse create(UUID userId, TaskCategoryCreateRequest req) {
        String name = validator.validateName(req.name());          // 422

        if (repository.existsByUserIdAndName(userId, name)) {       // 409 (AC① — UNIQUE(user_id,name))
            throw new OpenPlanException(ErrorCode.E_CAT_001);
        }

        TaskCategory category = new TaskCategory(userId, name, 0, clock.now());
        repository.save(category);
        return TaskCategoryResponse.from(category);
    }

    /**
     * 카테고리 목록 (SC-01 / AC③). 사용자 전체를 sort_order ASC, name ASC로 반환. 읽기 — 서비스 tx 없음.
     */
    public List<TaskCategoryResponse> list(UUID userId) {
        return repository.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .map(TaskCategoryResponse::from)
                .toList();
    }
}
