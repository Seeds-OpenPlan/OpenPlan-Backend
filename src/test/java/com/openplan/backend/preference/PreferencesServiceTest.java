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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 기본 설정 (FIX-10~12) — 값 규칙이 DB CHECK 와 어긋나지 않는지, "안 정함"이 오류가 아닌지를 고정한다.
 */
class PreferencesServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    private final Map<UUID, UserPreferences> store = new HashMap<>();
    private PreferencesService service;

    @BeforeEach
    void setUp() {
        UserPreferencesRepository repo = mock(UserPreferencesRepository.class);
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repo.save(any(UserPreferences.class))).thenAnswer(inv -> {
            UserPreferences p = inv.getArgument(0);
            store.put(p.getUserId(), p);
            return p;
        });
        UserClock clock = mock(UserClock.class);
        when(clock.now()).thenReturn(NOW);
        service = new PreferencesService(repo, new ErrorMessages(), clock);
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
}
