package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 제공자 → 어댑터 조회 (ST-B1-11).
 *
 * <p>구현이 없는 제공자는 <b>여기서 502 E-EXT-001 로 끝난다.</b> null 을 돌려주고 위에서 분기하게 하면
 * "연결은 됐는데 조회만 조용히 빈 목록"이라는 최악의 모양이 나온다 — 사용자는 일정이 없는 줄 안다.
 */
@Component
public class CalendarProviderRegistry {

    private final Map<ExternalCalendarProvider, CalendarProvider> providers =
            new EnumMap<>(ExternalCalendarProvider.class);

    public CalendarProviderRegistry(List<CalendarProvider> implementations) {
        for (CalendarProvider implementation : implementations) {
            providers.put(implementation.provider(), implementation);
        }
    }

    public CalendarProvider get(ExternalCalendarProvider provider) {
        CalendarProvider implementation = providers.get(provider);
        if (implementation == null) {
            throw new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", provider.name()));
        }
        return implementation;
    }

    /** 연동 생성 가능 여부 — 계약이 여는 제공자와 구현이 있는 제공자의 교집합. */
    public boolean supports(ExternalCalendarProvider provider) {
        return providers.containsKey(provider);
    }
}
