package com.openplan.backend.notification.repository;

import com.openplan.backend.notification.domain.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, UUID> {

    List<NotificationSetting> findByUserId(UUID userId);
}
