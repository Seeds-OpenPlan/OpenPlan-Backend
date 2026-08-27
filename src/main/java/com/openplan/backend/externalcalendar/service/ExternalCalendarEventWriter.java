package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 신규 후보 일정을 <b>바깥 트랜잭션과 분리해</b> 넣는다 (ST-B1-11 · 동기화 경합).
 *
 * <p><b>왜 별도 빈인가.</b> 동기화는 조회({@code listEvents})의 트랜잭션 안에서 돈다. 같은 트랜잭션에서
 * insert 가 UQ({@code ux_ext_event})를 위반하면, 그 예외를 잡아도 되돌릴 수 없다 — 두 겹으로 막힌다:
 * <ul>
 *   <li>실패한 flush 는 하이버네이트 세션을 못 쓰는 상태로 만든다(JPA 규약).</li>
 *   <li>참여 트랜잭션이 예외로 끝나면 스프링이 바깥을 {@code rollback-only} 로 표시하고,
 *       커밋에서 {@code UnexpectedRollbackException} 이 다시 터진다.</li>
 * </ul>
 * 그래서 "조용히 물러난다"고 적어 둔 catch 가 실제로는 <b>500 을 다른 얼굴로 바꿀 뿐</b>이었다
 * (2026-08-27 통합 테스트로 재현 — {@code ExternalCalendarApiTest.동기화_경합은_500이_아니다}).
 *
 * <p>{@link Propagation#REQUIRES_NEW} 는 바깥을 잠시 멈추고 <b>새 트랜잭션</b>을 연다. 실패하면 그
 * 트랜잭션만 되돌아가고 바깥은 멀쩡하므로, 진 쪽은 이긴 쪽이 넣은 행을 그대로 조회해 돌려줄 수 있다.
 *
 * <p>🔴 <b>여기서 예외를 잡지 않는다.</b> 잡아도 이 트랜잭션이 이미 rollback-only 라 커밋에서 다시
 * 터진다 — 잡는 자리는 트랜잭션 경계 <b>밖</b>, 즉 부르는 쪽이어야 한다.
 */
@Component
public class ExternalCalendarEventWriter {

    private final ExternalCalendarEventRepository eventRepository;

    public ExternalCalendarEventWriter(ExternalCalendarEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** 한 번에. 한 건이라도 겹치면 배치 전체가 되돌아간다 — 부르는 쪽이 {@link #insertOne} 으로 되짚는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertAll(List<ExternalCalendarEvent> created) {
        // flush 를 여기서 강제한다. ExternalCalendarEvent 는 UUID 를 앱이 직접 채워 @GeneratedValue 가
        // 없으므로 하이버네이트가 INSERT 를 미루는데, 그러면 위반이 이 트랜잭션 밖에서 터진다.
        eventRepository.saveAllAndFlush(created);
    }

    /** 한 건씩. 겹친 것만 실패시키고 나머지는 살린다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertOne(ExternalCalendarEvent created) {
        eventRepository.saveAndFlush(created);
    }
}
