package com.openplan.backend.externalcalendar.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 아웃박스를 밀어낸다 (#69 · 계획 D2·D5).
 *
 * <p><b>언제 도는가.</b> 별도 배치가 없다 — 동기화가 돌 때 함께 돈다. 이 저장소에는
 * {@code @Scheduled} 가 하나도 없고, ADR-0006 이 «단일 EC2 에서 배치 실패·중복 실행 관측 부담» 을
 * 들어 배치를 기각했다. 그 결정을 뒤집지 않는다.
 *
 * <p>🔴 <b>실행을 {@link OutboundOpExecutor} 에 위임한다.</b> 같은 클래스 안에서 부르면
 * 프록시를 타지 않아 {@code REQUIRES_NEW} 가 무시되고, 한 건의 결과가 바깥 트랜잭션에 얹혀
 * 함께 롤백된다 — 이미 외부에 만든 일정은 되돌릴 수 없는데 우리 기록만 사라진다(#79 리뷰).
 */
@Service
public class OutboundCalendarPusher {

    private static final Logger log = LoggerFactory.getLogger(OutboundCalendarPusher.class);

    private final OutboundCalendarOpRepository opRepository;
    private final OutboundOpExecutor executor;

    public OutboundCalendarPusher(OutboundCalendarOpRepository opRepository, OutboundOpExecutor executor) {
        this.opRepository = opRepository;
        this.executor = executor;
    }

    /**
     * 이 사용자의 대기열을 <b>만든 순서대로</b> 밀어낸다.
     *
     * <p>순서가 중요하다 — 같은 대상에 CREATE 뒤 DELETE 가 쌓였는데 거꾸로 보내면 <b>지운 일정이
     * 되살아난다.</b>
     *
     * <p>🔴 <b>예외를 올리지 않는다.</b> 동기화(조회) 경로에서 불리는데, 외부 쓰기가 실패했다고
     * 사용자의 조회가 실패하면 «남의 장애로 내 화면이 깨지는» 것이다.
     */
    public void pushPending(UUID userId) {
        List<OutboundCalendarOp> pending = opRepository.findPending(userId);
        for (OutboundCalendarOp op : pending) {
            try {
                executor.execute(op.getId());
            } catch (Exception e) {
                // executor 가 이미 사유를 적고 커밋했다. 여기서 다시 올리면 조회가 깨진다.
                log.warn("외부 캘린더 내보내기 실패 — 다음 동기화에서 다시 시도한다: opId={}", op.getId());
            }
        }
    }
}
