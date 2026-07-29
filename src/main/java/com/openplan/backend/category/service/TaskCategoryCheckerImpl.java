package com.openplan.backend.category.service;

import com.openplan.backend.category.repository.TaskCategoryRepository;
import com.openplan.backend.task.service.port.TaskCategoryChecker;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link TaskCategoryChecker} 정품 구현 (TB-6 이관 — ST-B2-04). ST-B2-03의 잠정 {@code JdbcTaskCategoryChecker}를
 * 대체한다. 카테고리 도메인이 자기 저장소({@link TaskCategoryRepository})로 소유 존재를 판정한다.
 *
 * <p>포트 인터페이스는 task 도메인 소유(소비자 계약)로 불변 — task/ 코드는 이 이관에 무영향(구현체만 교체).
 */
@Component
public class TaskCategoryCheckerImpl implements TaskCategoryChecker {

    private final TaskCategoryRepository repository;

    public TaskCategoryCheckerImpl(TaskCategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsOwned(UUID categoryId, UUID userId) {
        return repository.existsByIdAndUserId(categoryId, userId);
    }
}
