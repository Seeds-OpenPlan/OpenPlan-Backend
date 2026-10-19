package com.openplan.backend.externalcalendar.repository;

import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동기화 잠금·잠금 대기 상한 통합 테스트 (2026-10-19 운영 장애 — 같은 사용자의 동기화가 겹쳐 연결 풀 전체가 멈췄다).
 * 단위 테스트는 잠금 결과에 따른 분기만 본다 — 실제 PostgreSQL 에서 잠금이 «기다리지 않고» 거절되는지는 여기서 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class SyncLockIntegrationTest {

    @Autowired
    private ExternalCalendarConnectionRepository connectionRepository;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("한 트랜잭션이 쥔 동안 다른 트랜잭션은 즉시 false, 커밋하면 다시 잡힌다 — 사용자마다 따로")
    void 잠금은_트랜잭션_동안만_한_쪽만_잡는다() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = pool.submit(() -> tx.execute(status -> {
                boolean got = connectionRepository.tryLockSyncFor(user);
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return got;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            Boolean contended = tx.execute(status -> connectionRepository.tryLockSyncFor(user));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Boolean otherUser = tx.execute(status -> connectionRepository.tryLockSyncFor(other));

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(contended).as("쥔 쪽이 있으면 거절").isFalse();
            assertThat(waitedMs).as("기다리지 않는다 — 기다리면 연결을 쥔 채 서 있게 된다").isLessThan(2_000);
            assertThat(otherUser).as("다른 사용자는 막지 않는다").isTrue();
            Boolean afterCommit = tx.execute(status -> connectionRepository.tryLockSyncFor(user));
            assertThat(afterCommit).as("커밋하면 풀린다").isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("풀의 모든 연결에 잠금 대기 상한이 걸려 있다 — 없으면 교착에 빠진 요청이 연결을 영원히 쥔다")
    void 연결마다_lock_timeout_이_걸려_있다() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("10s");
    }
}
