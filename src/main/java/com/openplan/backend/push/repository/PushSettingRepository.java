package com.openplan.backend.push.repository;

import com.openplan.backend.push.domain.PushSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 푸시 설정 저장소 (ADR-0015 결정 ⑥). PK가 바로 {@code user_id}라 단건 조회가 {@code findById}다. */
public interface PushSettingRepository extends JpaRepository<PushSetting, UUID> {

    Optional<PushSetting> findByUserId(UUID userId);
}
