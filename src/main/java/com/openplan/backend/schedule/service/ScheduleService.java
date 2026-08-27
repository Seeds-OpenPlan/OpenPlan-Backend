package com.openplan.backend.schedule.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.schedule.domain.Schedule;
import com.openplan.backend.schedule.dto.ScheduleResponse;
import com.openplan.backend.schedule.dto.ScheduleUpdateRequest;
import com.openplan.backend.schedule.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 일정 유스케이스 (PLAN-17). 값 검증은 {@link ScheduleValidator}(생성과 공유). 소유·낙관락은 서비스가 판정한다.
 *
 * <p>본 슬라이스는 편집만. 시각(start/end)은 편집 대상이 아니다 — 시각 변경은 블록 이동(PLAN-19)이 담당한다.
 */
@Service
public class ScheduleService {

    private final ScheduleRepository repository;
    private final ScheduleValidator validator;

    public ScheduleService(ScheduleRepository repository, ScheduleValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * 일정 편집 (PLAN-17). <b>true PATCH(부분 수정)</b> — 담겨 온 필드만 검증·반영, 안 담긴 필드는 기존 값 유지.
     * 순서: 404(부재·타인) → 409(version 낙관락, latest 동봉) → 422(필드).
     */
    @Transactional
    public ScheduleResponse update(UUID userId, UUID scheduleId, ScheduleUpdateRequest req) {
        Schedule schedule = repository.findByIdAndUserId(scheduleId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        if (req.getVersion() != schedule.getVersion()) { // 409 — 최신본 동봉(SYS-05)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", ScheduleResponse.from(schedule)));
        }

        // 부분 병합 — 담겨 온 필드만 검증·반영
        String title = schedule.getTitle();
        if (req.isProvided("title")) {
            title = validator.validateTitle(req.getTitle());        // 422 — 생성과 동일 코드
        }
        Integer estimatedMinutes = schedule.getEstimatedMinutes();
        if (req.isProvided("estimatedMinutes")) {
            validator.validateEstimatedMinutes(req.getEstimatedMinutes()); // 422 (null 허용)
            estimatedMinutes = req.getEstimatedMinutes();
        }
        Integer priority = schedule.getPriority();
        if (req.isProvided("priority")) {
            validator.validatePriority(req.getPriority());          // 422 (null 허용)
            priority = req.getPriority();
        }
        String memo = req.isProvided("memo") ? req.getMemo() : schedule.getMemo();

        schedule.edit(title, estimatedMinutes, priority, memo);
        repository.flush(); // @Version 증가를 응답에 반영. 잔여 경합 → OptimisticLockException → 409
        return ScheduleResponse.from(schedule);
    }
}
