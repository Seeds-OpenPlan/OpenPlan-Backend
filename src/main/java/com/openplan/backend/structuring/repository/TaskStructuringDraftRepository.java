package com.openplan.backend.structuring.repository;

import com.openplan.backend.structuring.domain.TaskStructuringDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 구조화 초안 저장소 (SS-03). 소유는 소속 project(=user_id 스코프)로 판정 — 서비스가 프로젝트를 선판정한다. */
public interface TaskStructuringDraftRepository extends JpaRepository<TaskStructuringDraft, UUID> {

    /** 재생성 = 전면 교체. 아직 채택되지 않은 것만 지운다 — 채택 이력은 "무엇이 쓰였나"의 기록이라 남긴다. */
    long deleteByProjectIdAndAdoptedFalse(UUID projectId);

    List<TaskStructuringDraft> findByProjectIdOrderByCreatedAtAsc(UUID projectId);
}
