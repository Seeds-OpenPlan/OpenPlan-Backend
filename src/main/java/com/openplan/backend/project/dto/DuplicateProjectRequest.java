package com.openplan.backend.project.dto;

/**
 * 프로젝트 복제 요청 (PROJ-12 / 정본 openapi.yaml duplicateProject). 본문은 선택.
 * {@code newName} 미지정 시 서비스가 {@code "원본명 (복제)"}로 채운다.
 */
public record DuplicateProjectRequest(String newName) {
}
