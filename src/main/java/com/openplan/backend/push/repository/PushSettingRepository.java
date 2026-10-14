package com.openplan.backend.push.repository;

import com.openplan.backend.push.domain.PushSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 푸시 설정 저장소 (ADR-0015 결정 ⑥). PK가 바로 {@code user_id}라 단건 조회가 {@code findById}다. */
public interface PushSettingRepository extends JpaRepository<PushSetting, UUID> {

    Optional<PushSetting> findByUserId(UUID userId);

    /**
     * 설정 원자적 업서트(PUT — 있으면 갈아끼우고, 없으면 생성). {@code UserPreferencesRepository.upsert}와
     * 같은 이유다: "findByUserId 후 없으면 save" 는 원자적이지 않아, 아직 행이 없는 사용자의 PUT 이 거의
     * 동시에 두 번 오면(더블클릭·앱 재시도) 둘 다 INSERT 해 한쪽이 PK 위반 500이 된다(PR #93 리뷰).
     * {@code ON CONFLICT}는 예외 자체를 없애 두 요청 모두 200으로 수렴시킨다.
     *
     * <p>PUT 은 전체 교체 계약이라 last-write-wins. {@code created_at}은 최초 삽입 값을 유지한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO push_settings
                (user_id, task_enabled, fixed_schedule_enabled, schedule_enabled, lead_minutes,
                 updated_at, created_at)
            VALUES (:userId, :taskEnabled, :fixedScheduleEnabled, :scheduleEnabled, :leadMinutes, :now, :now)
            ON CONFLICT (user_id) DO UPDATE
               SET task_enabled = EXCLUDED.task_enabled,
                   fixed_schedule_enabled = EXCLUDED.fixed_schedule_enabled,
                   schedule_enabled = EXCLUDED.schedule_enabled,
                   lead_minutes = EXCLUDED.lead_minutes,
                   updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId,
                @Param("taskEnabled") boolean taskEnabled,
                @Param("fixedScheduleEnabled") boolean fixedScheduleEnabled,
                @Param("scheduleEnabled") boolean scheduleEnabled,
                @Param("leadMinutes") int leadMinutes,
                @Param("now") Instant now);
}
