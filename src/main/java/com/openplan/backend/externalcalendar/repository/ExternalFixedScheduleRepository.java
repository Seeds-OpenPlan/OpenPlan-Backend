package com.openplan.backend.externalcalendar.repository;

import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * 연동 유래 고정 일정에만 닿는 저장소 (ST-B1-11 AC3 미러).
 *
 * <p><b>왜 {@code FixedScheduleRepository} 에 메서드를 더하지 않았나.</b> 그 파일은 고정 일정 레인의
 * 것이고 지금 열려 있는 PR 들이 함께 건드리는 자리다 — 같은 파일에 각자 메서드를 더하면 #26↔#28 의
 * {@code zoneOf} 충돌과 같은 일이 반복된다. 연동이 필요로 하는 것은 <b>connection_id 기준 일괄 조작</b>
 * 하나뿐이라 이쪽에 따로 둔다.
 */
public interface ExternalFixedScheduleRepository extends JpaRepository<FixedSchedule, UUID> {

    /**
     * FIX-16 미러 — 연동 활성/비활성을 유래 고정 일정에 그대로 옮긴다(AC3, 같은 트랜잭션).
     *
     * <p>건별로 읽어 고치지 않고 한 문장으로 처리하는 이유는, 연동 하나에 딸린 일정이 수십 건일 수 있고
     * 이 조작에 낙관락 경합을 만들 이유가 없기 때문이다.
     *
     * @return 바뀐 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FixedSchedule fs set fs.status = :status where fs.connectionId = :connectionId")
    int updateStatusByConnectionId(@Param("connectionId") UUID connectionId,
                                   @Param("status") FixedScheduleStatus status);
}
