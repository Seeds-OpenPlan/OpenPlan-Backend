package com.openplan.backend.project.repository;

import com.openplan.backend.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 프로젝트 저장소 (ST-B2-01). 소유자 스코프 조회·자동종료 벌크 평가 등은 이후 슬라이스에서 추가한다.
 * 생성(C) 슬라이스는 상속 {@code save}만 사용한다.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {
}
