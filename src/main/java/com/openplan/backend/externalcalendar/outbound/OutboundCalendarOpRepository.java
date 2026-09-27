package com.openplan.backend.externalcalendar.outbound;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboundCalendarOpRepository extends JpaRepository<OutboundCalendarOp, UUID> {

    /**
     * 아직 안 나간 것을 <b>만든 순서대로</b>. 순서가 중요하다 — 같은 대상에 CREATE 뒤 DELETE 가
     * 쌓였는데 거꾸로 보내면 지운 일정이 되살아난다.
     *
     * <p>FAILED 도 함께 집는다. 실패 대부분이 토큰 만료·연결 끊김처럼 사용자가 고치면 풀리는
     * 것이라, 한 번 실패했다고 빼 두면 사용자가 고친 뒤에도 안 나간다.
     */
    @Query("""
            select o from OutboundCalendarOp o
             where o.userId = :userId
               and o.status <> com.openplan.backend.externalcalendar.outbound.OutboundStatus.DONE
             order by o.createdAt asc
            """)
    List<OutboundCalendarOp> findPending(@Param("userId") UUID userId);
}
