package com.openplan.backend.preference.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.preference.domain.UserPreferences;
import com.openplan.backend.preference.dto.PreferencesRequest;
import com.openplan.backend.preference.dto.PreferencesResponse;
import com.openplan.backend.preference.repository.UserPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 사용자 기본 설정 (FIX-10·11·12).
 *
 * <p><b>없어도 404 가 아니다.</b> 설정한 적이 없는 것은 정상 상태라 빈 값을 돌려준다 — 404 를 주면
 * 화면이 "오류" 와 "안 정함" 을 구분하지 못한다.
 *
 * <p><b>PUT 은 전체 교체다.</b> 담겨 오지 않은 값은 지워진다. 계약이 PATCH 가 아니라 PUT 인 이상
 * "되돌리기" 를 표현할 방법이 이것뿐이다.
 */
@Service
public class PreferencesService {

    /** ck_user_pref_replan 과 같은 값. 코드와 DB 가 갈라지면 요청값이 500 으로 떨어진다. */
    private static final Set<String> STRATEGIES =
            Set.of("KEEP_CURRENT", "MINIMAL_CHANGE", "DEADLINE_FIRST", "WORKLOAD_BALANCE");

    private final UserPreferencesRepository repository;
    private final ErrorMessages errorMessages;
    private final UserClock clock;

    public PreferencesService(UserPreferencesRepository repository,
                              ErrorMessages errorMessages, UserClock clock) {
        this.repository = repository;
        this.errorMessages = errorMessages;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(UUID userId) {
        return repository.findById(userId)
                .map(PreferencesResponse::from)
                .orElseGet(PreferencesResponse::empty);
    }

    @Transactional
    public PreferencesResponse save(UUID userId, PreferencesRequest req) {
        validate(req);

        UserPreferences prefs = repository.findById(userId)
                .orElseGet(() -> repository.save(new UserPreferences(userId, clock.now())));
        prefs.replace(req.defaultEstimatedMinutes(), req.defaultReplanStrategy(),
                req.weeklyAvailableMinutes(), clock.now());

        return PreferencesResponse.from(prefs);
    }

    /** 값 규칙을 DB CHECK 와 같은 자리에서 읽히게 둔다 — 어긋나면 요청이 422 가 아니라 500 이 된다. */
    private void validate(PreferencesRequest req) {
        requireFiveMinuteStep(req.defaultEstimatedMinutes(), "defaultEstimatedMinutes");
        requireFiveMinuteStep(req.weeklyAvailableMinutes(), "weeklyAvailableMinutes");

        String strategy = req.defaultReplanStrategy();
        if (strategy != null && !STRATEGIES.contains(strategy)) {
            throw invalid("defaultReplanStrategy", "invalid");
        }
    }

    /** null 은 "설정하지 않음" 이라 통과시킨다. 0·음수·5분 미배수만 막는다(DB CHECK 와 동일). */
    private void requireFiveMinuteStep(Integer minutes, String field) {
        if (minutes == null) {
            return;
        }
        if (minutes <= 0 || minutes % 5 != 0) {
            throw invalid(field, "step");
        }
    }

    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
