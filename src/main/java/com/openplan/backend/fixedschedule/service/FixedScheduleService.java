package com.openplan.backend.fixedschedule.service;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import com.openplan.backend.fixedschedule.dto.FixedScheduleCreateRequest;
import com.openplan.backend.fixedschedule.dto.FixedScheduleResponse;
import com.openplan.backend.fixedschedule.dto.FixedScheduleUpdateRequest;
import com.openplan.backend.fixedschedule.repository.FixedScheduleRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 고정 일정 유스케이스 파사드 (FIX-04~09). 검증은 {@link FixedScheduleValidator}, 시각은 {@link UserClock}.
 *
 * <p>본 슬라이스는 <b>생성(MANUAL) + 목록</b>만 구현한다. 편집(FIX-06)·삭제(FIX-09)·주차예외(PLAN-33/34)·
 * 충돌 미리보기(FIX-07/08)·EXTERNAL 유래는 후속. 생성·삭제는 저장된 주간 계획 재검증 트리거(FIX-07/09)를
 * 수반해야 하나, 검증 엔진 라우트(ST-B2-09) 완성 전까지는 데이터 계약만 선행한다.
 */
@Service
public class FixedScheduleService {

    private final FixedScheduleRepository repository;
    private final FixedScheduleValidator validator;
    private final UserClock clock;

    public FixedScheduleService(FixedScheduleRepository repository, FixedScheduleValidator validator, UserClock clock) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * 고정 일정 생성 (FIX-05). title·weekday·시각·기간 검증(422) → MANUAL 저장(source=MANUAL·status=ACTIVE).
     * 이후 이 시간대는 주간 계획에서 배치 불가로 반영된다(검증 엔진 V2 소비).
     */
    @Transactional
    public FixedScheduleResponse create(UUID userId, FixedScheduleCreateRequest req) {
        String title = validator.validateTitle(req.title());
        Weekday weekday = validator.resolveWeekday(req.weekday());
        validator.validateTimes(req.startTime(), req.endTime());
        validator.validateDates(req.startDate(), req.endDate());

        FixedSchedule fs = FixedSchedule.createManual(
                userId, title, weekday, req.startTime(), req.endTime(),
                req.startDate(), req.endDate(), clock.now());
        repository.save(fs);
        return FixedScheduleResponse.from(fs);
    }

    /**
     * 고정 일정 편집 (FIX-06). <b>true PATCH(부분 수정)</b> — 담겨 온 필드만 검증·반영, 안 담긴 필드는 기존 값 유지.
     * 순서: 404(부재·타인) → 409(version 낙관락, latest 동봉) → 422(필드). 편집 후 저장된 주간 계획 재검증(FIX-07)은
     * 검증 엔진 라우트(ST-B2-09) 소관 — 여기선 값만 갱신한다.
     */
    @Transactional
    public FixedScheduleResponse update(UUID userId, UUID id, FixedScheduleUpdateRequest req) {
        FixedSchedule fs = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        if (req.getVersion() != fs.getVersion()) { // 409 — 최신본 동봉(SYS-05)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", FixedScheduleResponse.from(fs)));
        }

        // 부분 병합 — 담겨 온 필드만 검증·반영, 안 담긴 필드는 기존 값 유지(true PATCH).
        String title = fs.getTitle();
        if (req.isProvided("title")) {
            title = validator.validateTitle(req.getTitle());           // 422 — 생성과 동일 코드
        }
        Weekday weekday = fs.getWeekday();
        if (req.isProvided("weekday")) {
            weekday = validator.resolveWeekday(req.getWeekday());       // 422
        }

        // 시각은 쌍 규칙(시작<종료)이라, 한쪽만 바뀌어도 병합된 최종 쌍으로 검증한다.
        LocalTime startTime = fs.getStartTime();
        LocalTime endTime = fs.getEndTime();
        if (req.isProvided("startTime")) {
            startTime = req.getStartTime();
        }
        if (req.isProvided("endTime")) {
            endTime = req.getEndTime();
        }
        if (req.isProvided("startTime") || req.isProvided("endTime")) {
            validator.validateTimes(startTime, endTime);               // 422
        }

        LocalDate startDate = req.isProvided("startDate") ? req.getStartDate() : fs.getStartDate();
        LocalDate endDate = req.isProvided("endDate") ? req.getEndDate() : fs.getEndDate();
        if (req.isProvided("startDate") || req.isProvided("endDate")) {
            validator.validateDates(startDate, endDate);               // 422
        }

        fs.edit(title, weekday, startTime, endTime, startDate, endDate);
        repository.flush(); // @Version 증가를 응답에 반영. 잔여 경합 → OptimisticLockException → 409
        return FixedScheduleResponse.from(fs);
    }

    /**
     * 고정 일정 삭제 (FIX-09). hard delete. 부재·타인 → 404 E-COM-004(존재 은닉, 멱등 아님).
     * 주차 예외({@code fixed_schedule_week_exceptions})는 FK ON DELETE CASCADE로 DB가 함께 지운다(앱 별도 처리 불요).
     * 삭제 후 주간 계획 재검증(FIX-09)은 검증 엔진 라우트(ST-B2-09) 소관.
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        FixedSchedule fs = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404
        repository.delete(fs);
    }

    /**
     * 고정 일정 목록 (FIX-04). status 미지정 시 전체, 지정 시 해당 상태만. weekday → start_time 순. 읽기 — tx 없음.
     */
    public List<FixedScheduleResponse> list(UUID userId, FixedScheduleStatus status) {
        List<FixedSchedule> found = (status == null)
                ? repository.findByUserIdOrderByWeekdayAscStartTimeAsc(userId)
                : repository.findByUserIdAndStatusOrderByWeekdayAscStartTimeAsc(userId, status);
        return found.stream().map(FixedScheduleResponse::from).toList();
    }
}
