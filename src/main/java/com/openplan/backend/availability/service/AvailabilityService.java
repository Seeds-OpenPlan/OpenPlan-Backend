package com.openplan.backend.availability.service;

import com.openplan.backend.availability.domain.AvailabilityPattern;
import com.openplan.backend.availability.dto.AvailabilityPatternDto;
import com.openplan.backend.availability.dto.AvailabilityView;
import com.openplan.backend.availability.dto.SaveAvailabilitiesRequest;
import com.openplan.backend.availability.repository.AvailabilityPatternRepository;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 가용 시간 서비스(ST-B1-09) — 조회 및 전체 교체 저장(요일 7행). 계약은 캘린더/설정 공통(PLAN-32도 이 PUT).
 *
 * <p>저장은 검증 → 기존 삭제 → 7행 재삽입(멱등). 5분 단위·시작&lt;종료·7요일 완전성을 서버에서 검증해
 * DB CHECK와 이중 방어한다(422 E-COM-009). 주간 총합은 저장/조회 응답에서 파생 계산한다(컬럼 아님).
 */
@Service
public class AvailabilityService {

    private static final int STEP_SECONDS = 300; // 5분 (NFR-010)

    private final AvailabilityPatternRepository repository;

    public AvailabilityService(AvailabilityPatternRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AvailabilityView getMyAvailabilities(UUID userId) {
        return toView(repository.findByUserId(userId));
    }

    /**
     * 요일 7행을 통째로 교체 저장한다. 벌크 삭제를 flush로 확정한 뒤 재삽입해 UQ(user_id, weekday) 충돌을 피한다.
     */
    @Transactional
    public AvailabilityView saveAvailabilities(UUID userId, SaveAvailabilitiesRequest request) {
        validate(request.patterns());

        repository.deleteByUserId(userId);
        repository.flush(); // DELETE를 INSERT보다 먼저 DB에 반영 — 같은 요일 재삽입 시 UQ 위반 방지

        List<AvailabilityPattern> entities = request.patterns().stream()
                .map(d -> AvailabilityPattern.create(userId, d.weekday(), d.startTime(), d.endTime(), d.isActive()))
                .toList();
        return toView(repository.saveAll(entities));
    }

    /** 응답 조립 — 요일 순 정렬 + 활성 패턴의 주간 총합(분). */
    private AvailabilityView toView(List<AvailabilityPattern> patterns) {
        List<AvailabilityPatternDto> dtos = patterns.stream()
                .sorted(Comparator.comparingInt(p -> p.getWeekday().ordinal()))
                .map(AvailabilityPatternDto::from)
                .toList();

        int weeklyTotalMinutes = patterns.stream()
                .filter(AvailabilityPattern::isActive)
                .mapToInt(p -> (int) Duration.between(p.getStartTime(), p.getEndTime()).toMinutes())
                .sum();

        return new AvailabilityView(dtos, weeklyTotalMinutes);
    }

    /**
     * 시맨틱 검증(→ E-COM-009): ①요일 7개가 중복 없이 MON..SUN 전부 ②각 행 5분 단위 ③시작&lt;종료.
     * 개수(정확히 7)는 컨트롤러 @Size가 이미 강제하지만, 서비스 직접 호출 대비 여기서도 완전성을 확인한다.
     */
    private void validate(List<AvailabilityPatternDto> patterns) {
        Set<Weekday> weekdays = EnumSet.noneOf(Weekday.class);
        for (AvailabilityPatternDto p : patterns) {
            weekdays.add(p.weekday());
        }
        if (weekdays.size() != patterns.size() || weekdays.size() != Weekday.values().length) {
            throw new OpenPlanException(ErrorCode.E_COM_009,
                    Map.of("field", "patterns", "rule", "weekday_set"));
        }

        for (AvailabilityPatternDto p : patterns) {
            if (!isFiveMinuteBoundary(p.startTime()) || !isFiveMinuteBoundary(p.endTime())) {
                throw new OpenPlanException(ErrorCode.E_COM_009,
                        Map.of("field", "patterns", "weekday", p.weekday().name(), "rule", "step"));
            }
            if (!p.startTime().isBefore(p.endTime())) {
                throw new OpenPlanException(ErrorCode.E_COM_009,
                        Map.of("field", "patterns", "weekday", p.weekday().name(), "rule", "range"));
            }
        }
    }

    private boolean isFiveMinuteBoundary(LocalTime time) {
        return time.toSecondOfDay() % STEP_SECONDS == 0;
    }
}
