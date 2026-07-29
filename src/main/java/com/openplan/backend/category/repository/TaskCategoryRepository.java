package com.openplan.backend.category.repository;

import com.openplan.backend.category.domain.TaskCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 태스크 카테고리 저장소 (ST-B2-04). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 */
public interface TaskCategoryRepository extends JpaRepository<TaskCategory, UUID> {

    /** 이름 중복 사전 판정 — true면 서비스가 409 E-CAT-001로 라우팅(DB UNIQUE(user_id,name)이 백스톱). */
    boolean existsByUserIdAndName(UUID userId, String name);
}
