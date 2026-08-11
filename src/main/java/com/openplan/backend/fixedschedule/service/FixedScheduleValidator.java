package com.openplan.backend.fixedschedule.service;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 고정 일정 도메인 값 검증 (422 E-COM-009). 생성(FIX-05)·편집(FIX-06)이 동일 메서드를 재사용하도록 설계한다.
 * 필드 메시지는 {@code messages/errors.properties}에서 {@code validation.{field}.{rule}} 키로 해석한다(단일 원천).
 *
 * <p>DB 제약(ck_fixed_range·ck_fixed_step·ck_fixed_weekday·ck_fixed_dates)과 이중 방어한다.
 */
@Component
public class FixedScheduleValidator {

    private static final int TITLE_MAX = 255;
    private static final int STEP_SECONDS = 300; // 5분 (NFR-010)

    private final ErrorMessages errorMessages;

    public FixedScheduleValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    /** title 규칙: null·공백만 → 실패, trim 후 1~255자(VARCHAR(255) 정합). @return trim된 제목. */
    public String validateTitle(String title) {
        String trimmed = (title == null) ? "" : title.trim(); // 첫 줄 null 가드
        if (trimmed.isEmpty()) {
            throw invalid("title", "required");
        }
        if (trimmed.length() > TITLE_MAX) {
            throw invalid("fixedScheduleTitle", "size");
        }
        return trimmed;
    }

    /**
     * weekday 규칙: null/미정의 문자열 → 실패. String으로 받아 여기서 enum 매핑(파싱 단계 500 회피).
     * @return 매핑된 {@link Weekday}.
     */
    public Weekday resolveWeekday(String weekday) {
        if (weekday == null) {
            throw invalid("weekday", "invalid");
        }
        try {
            return Weekday.valueOf(weekday.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("weekday", "invalid");
        }
    }

    /** 시각 규칙: 둘 다 필수 → 각 5분 단위 → 시작&lt;종료(ck_fixed_range·ck_fixed_step). */
    public void validateTimes(LocalTime startTime, LocalTime endTime) {
        if (startTime == null) {
            throw invalid("startTime", "required");
        }
        if (endTime == null) {
            throw invalid("endTime", "required");
        }
        if (!isFiveMinuteBoundary(startTime)) {
            throw invalid("startTime", "step");
        }
        if (!isFiveMinuteBoundary(endTime)) {
            throw invalid("endTime", "step");
        }
        if (!startTime.isBefore(endTime)) {
            throw invalid("endTime", "range");
        }
    }

    /** 기간 규칙(선택): 둘 다 있으면 startDate&le;endDate(ck_fixed_dates). 한쪽만/둘 다 null은 허용. */
    public void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw invalid("endDate", "range");
        }
    }

    private boolean isFiveMinuteBoundary(LocalTime time) {
        return time.toSecondOfDay() % STEP_SECONDS == 0;
    }

    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
