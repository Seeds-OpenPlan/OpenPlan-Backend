package com.openplan.backend.preference;

import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.preference.domain.UserPreferences;
import com.openplan.backend.preference.dto.PreferencesRequest;
import com.openplan.backend.preference.dto.PreferencesResponse;
import com.openplan.backend.preference.repository.UserPreferencesRepository;
import com.openplan.backend.preference.service.PreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기본 설정 (FIX-10~12) — 값 규칙이 DB CHECK 와 어긋나지 않는지, "안 정함"이 오류가 아닌지를 고정한다.
 *
 * <p>{@link #업서트를_쓴다_findorcreate_아니다()}·{@link #동시_첫_저장_경합_안전()}은 리뷰 Blocking
 * (PR #41 — {@code PreferencesService.save()}의 find-or-create 경합)에 대한 회귀 방지다.
 * 이 테스트는 Docker 가 죽어 있어(2026-08-25 기준) 실제 Postgres 유니크 제약을 태우지 못한다 —
 * {@code ON CONFLICT}의 원자성 자체는 Postgres 가 보장하는 부분이라 단위 테스트 대상이 아니고,
 * 여기서 고정하는 것은 "서비스가 find-or-create 를 다시 쓰지 않는다"는 계약이다.
 */
class PreferencesServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    private final Map<UUID, UserPreferences> store = new HashMap<>();
    private UserPreferencesRepository repo;
    private PreferencesService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserPreferencesRepository.class);
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        stubUpsert(repo, store);
        UserClock clock = mock(UserClock.class);
        when(clock.now()).thenReturn(NOW);
        service = new PreferencesService(repo, new ErrorMessages(), clock);
    }

    /**
     * {@code repository.upsert(...)}를 실제 {@code INSERT ... ON CONFLICT DO UPDATE}처럼 동작시킨다
     * — 없으면 만들고 있으면 덮어쓴다. {@code store} 자체를 모니터로 잠가 키 단위 원자성을 흉내낸다
     * (Postgres 의 행 단위 유니크 제약이 주는 보장과 같은 결과 — 겹치는 요청은 순서대로 수렴하고
     * 예외를 던지지 않는다). {@code HashMap}·{@code ConcurrentHashMap} 어느 쪽으로 넘겨도 안전하다.
     */
    private static void stubUpsert(UserPreferencesRepository repo, Map<UUID, UserPreferences> store) {
        doAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            Integer estimated = inv.getArgument(1);
            String strategy = inv.getArgument(2);
            Integer weekly = inv.getArgument(3);
            Instant now = inv.getArgument(4);
            synchronized (store) {
                UserPreferences existing = store.get(userId);
                UserPreferences p = existing != null ? existing : new UserPreferences(userId, now);
                p.replace(estimated, strategy, weekly, now);
                store.put(userId, p);
            }
            return null;
        }).when(repo).upsert(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("설정한 적이 없으면 404 가 아니라 빈 값을 준다 — '안 정함'은 정상 상태다")
    void 없으면_빈값() {
        PreferencesResponse r = service.get(USER);

        assertThat(r.defaultEstimatedMinutes()).isNull();
        assertThat(r.defaultReplanStrategy()).isNull();
        assertThat(r.weeklyAvailableMinutes()).isNull();
    }

    @Test
    @DisplayName("저장하면 그대로 돌아온다 — 주간 가용 시간 목표 포함")
    void 저장과_조회() {
        service.save(USER, new PreferencesRequest(60, "DEADLINE_FIRST", 2700));

        PreferencesResponse r = service.get(USER);
        assertThat(r.defaultEstimatedMinutes()).isEqualTo(60);
        assertThat(r.defaultReplanStrategy()).isEqualTo("DEADLINE_FIRST");
        assertThat(r.weeklyAvailableMinutes()).isEqualTo(2700);
    }

    @Test
    @DisplayName("PUT 은 전체 교체다 — 담겨 오지 않은 값은 지워진다")
    void 전체_교체() {
        service.save(USER, new PreferencesRequest(60, "DEADLINE_FIRST", 2700));
        service.save(USER, new PreferencesRequest(null, null, 1800));

        PreferencesResponse r = service.get(USER);
        assertThat(r.defaultEstimatedMinutes()).isNull();
        assertThat(r.defaultReplanStrategy()).isNull();
        assertThat(r.weeklyAvailableMinutes()).isEqualTo(1800);
    }

    @Test
    @DisplayName("null 은 '설정하지 않음'이라 통과한다")
    void null_은_유효하다() {
        assertThat(service.save(USER, new PreferencesRequest(null, null, null)).weeklyAvailableMinutes())
                .isNull();
    }

    @Test
    @DisplayName("5분 단위·양수 규칙이 DB CHECK 와 같다 — 어긋나면 422 가 아니라 500 이 된다")
    void 값_규칙() {
        assertThatThrownBy(() -> service.save(USER, new PreferencesRequest(7, null, null)))
                .isInstanceOf(OpenPlanException.class);
        assertThatThrownBy(() -> service.save(USER, new PreferencesRequest(0, null, null)))
                .isInstanceOf(OpenPlanException.class);
        assertThatThrownBy(() -> service.save(USER, new PreferencesRequest(null, null, -60)))
                .isInstanceOf(OpenPlanException.class);
        assertThatThrownBy(() -> service.save(USER, new PreferencesRequest(null, null, 33)))
                .isInstanceOf(OpenPlanException.class);
    }

    @Test
    @DisplayName("전략은 ck_user_pref_replan 의 4값 중 하나만 받는다")
    void 전략_enum() {
        for (String ok : new String[]{"KEEP_CURRENT", "MINIMAL_CHANGE", "DEADLINE_FIRST", "WORKLOAD_BALANCE"}) {
            assertThat(service.save(USER, new PreferencesRequest(null, ok, null)).defaultReplanStrategy())
                    .isEqualTo(ok);
        }
        assertThatThrownBy(() -> service.save(USER, new PreferencesRequest(null, "AGGRESSIVE", null)))
                .isInstanceOf(OpenPlanException.class);
    }

    @Test
    @DisplayName("save() 는 원자적 업서트를 쓴다 — findById 후 별도 save 로 만드는 find-or-create 가 아니다 (리뷰 Blocking, PR #41)")
    void 업서트를_쓴다_findorcreate_아니다() {
        service.save(USER, new PreferencesRequest(60, "DEADLINE_FIRST", 2700));

        verify(repo).upsert(USER, 60, "DEADLINE_FIRST", 2700, NOW);
        // find-or-create 로 되돌리면 이 줄에서 반드시 실패한다 — repository.save(new UserPreferences(...))
        // 를 다시 호출하는 순간 여기 verify(never())가 걸린다(로컬에서 되돌려 확인, PR 설명 참고).
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("동시 첫 저장이 경합해도 500 없이 수렴한다 — find-or-create 였다면 하나는 PK 위반 500 (리뷰 Blocking, PR #41)")
    void 동시_첫_저장_경합_안전() throws Exception {
        // 별도 repo·store — ConcurrentHashMap 이라 findById 의 동시 읽기가 다른 스레드의 upsert 쓰기와
        // 부딪혀도(HashMap 이었다면 resize 중 손상 가능) 안전하다. 진짜 Postgres 유니크 제약을
        // 태우진 못하지만(Docker 다운, 2026-08-25) "경합 상황에서 save() 호출부가 예외 없이 끝나는가"는
        // 스레드로 직접 확인한다 — find-or-create 로 되돌리면 verify(repo, never()).save(any())가
        // 먼저 잡아내므로(바로 위 테스트) 이 테스트는 그 회귀를 스레드 재현으로 보강하는 역할이다.
        Map<UUID, UserPreferences> raceStore = new ConcurrentHashMap<>();
        UserPreferencesRepository raceRepo = mock(UserPreferencesRepository.class);
        when(raceRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(raceStore.get(inv.getArgument(0))));
        stubUpsert(raceRepo, raceStore);
        UserClock clock = mock(UserClock.class);
        when(clock.now()).thenReturn(NOW);
        PreferencesService raceService = new PreferencesService(raceRepo, new ErrorMessages(), clock);

        UUID user = UUID.fromString("22222222-2222-2222-2222-222222222222");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PreferencesResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return raceService.save(user, new PreferencesRequest(60, "DEADLINE_FIRST", 1800));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<PreferencesResponse> f : futures) {
                assertThatCode(() -> f.get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(raceStore).containsKey(user);
        assertThat(raceStore.get(user).getDefaultEstimatedMinutes()).isEqualTo(60);
    }
}
