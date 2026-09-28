package com.openplan.backend.externalcalendar.outbound;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 고정 일정 회차를 <b>조회 트랜잭션과 분리해</b> 넣는다 (#81 리뷰 Blocking).
 *
 * <p>회차 생성은 조회({@code listEvents})의 트랜잭션 안에서 돈다. 같은 트랜잭션에서 INSERT 가
 * {@code ux_fixed_occurrence} 를 위반하면 PostgreSQL 이 그 트랜잭션을 abort 상태로 만들어, 예외를
 * 잡아도 뒤따르는 모든 SQL 이 실패하고 조회가 500 으로 끝난다. {@link Propagation#REQUIRES_NEW} 로
 * 실패를 이 트랜잭션에 가둔다 — {@code ExternalCalendarEventWriter} 와 같은 이유, 같은 방식이다.
 *
 * <p>🔴 <b>여기서 예외를 잡지 않는다.</b> 잡는 자리는 트랜잭션 경계 밖, 부르는 쪽이다.
 */
@Component
public class FixedOccurrenceWriter {

    private final FixedOccurrenceRepository occurrenceRepository;

    public FixedOccurrenceWriter(FixedOccurrenceRepository occurrenceRepository) {
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FixedOccurrence reserve(FixedOccurrence occurrence) {
        return occurrenceRepository.saveAndFlush(occurrence);
    }
}
