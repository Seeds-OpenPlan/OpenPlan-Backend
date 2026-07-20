package com.openplan.backend.availability.repository;

import com.openplan.backend.availability.domain.AvailabilityPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 가용 시간 리포지토리. 조회는 요일 순서 보장을 위해 정렬 없이 받고 서비스가 요일 순 정렬한다(문자열 정렬≠요일 순서).
 */
public interface AvailabilityPatternRepository extends JpaRepository<AvailabilityPattern, UUID> {

    List<AvailabilityPattern> findByUserId(UUID userId);

    /**
     * 전체 교체 저장의 선행 삭제. 벌크 DELETE라 즉시 실행되어 UQ(user_id, weekday) 충돌 없이 재삽입할 수 있다.
     * (영속성 컨텍스트를 우회하므로 같은 트랜잭션에서 이후 saveAll과 순서만 지키면 안전.)
     */
    @Modifying
    @Query("delete from AvailabilityPattern a where a.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
