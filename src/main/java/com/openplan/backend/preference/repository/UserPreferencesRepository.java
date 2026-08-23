package com.openplan.backend.preference.repository;

import com.openplan.backend.preference.domain.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 사용자 기본 설정 저장소 (FIX-10~12). PK 가 user_id 라 소유 판정이 조회 자체에 들어 있다. */
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
}
