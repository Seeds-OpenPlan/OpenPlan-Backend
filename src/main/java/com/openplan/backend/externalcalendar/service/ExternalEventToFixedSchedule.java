package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import com.openplan.backend.externalcalendar.dto.ApplyEventRequest;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.service.FixedScheduleValidator;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 제공자 일정 → 고정 일정 변환 (ONB-09 · ST-B1-11 AC2).
 *
 * <p>두 모델이 어긋나 있어 변환에 판단이 필요하다:
 * <ul>
 *   <li><b>시각의 단위</b> — 제공자 일정은 분 단위 아무 값이나 되지만 고정 일정은 5분 배수여야 한다
 *       (ck_fixed_step). 시작은 <b>내림</b>, 종료는 <b>올림</b>으로 맞춘다 — 넓히는 쪽으로 어긋나야
 *       실제 약속 위에 계획이 얹히지 않는다. 좁히면 그 반대가 된다.</li>
 *   <li><b>반복의 성격</b> — 제공자 일정은 특정 날짜의 1회, 고정 일정은 요일 반복이다.
 *       {@code startDate=endDate=그 날짜}로 반복을 하루에 가둔다.</li>
 *   <li><b>사용자가 준 재정의 값</b> — {@code edited} 의 시각은 격자 보정을 거치지 않으므로
 *       {@link FixedScheduleValidator#validateTimes} 로 생성·편집과 같은 규칙을 적용한다.
 *       안 하면 5분 단위가 아닌 값이 DB CHECK 까지 내려가 422 대신 500 이 된다.</li>
 *   <li><b>날짜를 넘기는 일정</b> — 고정 일정은 하루 안에서 시작&lt;종료여야 한다(ck_fixed_range).
 *       자정을 넘는 일정은 그대로 옮길 수 없어 <b>422로 되돌리고 사용자가 EDITED 로 정하게</b> 한다.
 *       임의로 자정까지 잘라 넣으면 남은 시간이 비어 보여 그 위에 계획이 얹힌다.</li>
 * </ul>
 */
@Component
public class ExternalEventToFixedSchedule {

    private static final int STEP_MINUTES = 5;

    /** 하루의 마지막 5분 경계. 종료가 자정으로 올림될 때 여기로 붙인다. */
    private static final LocalTime LAST_STEP_OF_DAY = LocalTime.of(23, 55);

    private final UserClock userClock;

    /**
     * 🔴 고정 일정 값 검증의 정본이다 — 생성(FIX-05)·편집(FIX-06)이 이미 재사용하고 있다.
     * ONB-09 경로만 이것을 안 부르고 있었고, 그래서 {@code edited} 로 들어온 5분 단위 아닌 시각이
     * 검증 없이 DB 까지 내려가 {@code ck_fixed_step} 위반 → 처리되지 않는 500 이 됐다
     * (2026-08-27 리뷰 지적). 계약이 이 자리에서 기대하는 것은 422 E-COM-009 다.
     */
    private final FixedScheduleValidator validator;

    public ExternalEventToFixedSchedule(UserClock userClock, FixedScheduleValidator validator) {
        this.userClock = userClock;
        this.validator = validator;
    }

    /**
     * @param edited {@code mode=EDITED}의 재정의 값. null 이거나 개별 필드가 null 이면 원본 값을 쓴다.
     */
    public FixedSchedule convert(UUID userId, ExternalCalendarEvent event, ApplyEventRequest.Edited edited) {
        ZoneId zone = userClock.zoneOf(userId);
        ZonedDateTime start = event.getStartAt().atZone(zone);
        ZonedDateTime end = event.getEndAt().atZone(zone);

        LocalDate date = start.toLocalDate();
        Weekday weekday = weekdayOf(start);
        LocalTime startTime = floorToStep(start.toLocalTime());
        LocalTime endTime = ceilToStep(end.toLocalTime());

        boolean crossesMidnight = !end.toLocalDate().equals(date);
        if (crossesMidnight) {
            // 종료가 다음 날이면 하루 안의 구간으로 옮길 방법이 없다.
            requireEditedTimes(edited, "자정을 넘는 일정입니다");
        }

        if (edited != null) {
            if (edited.weekday() != null) {
                weekday = edited.weekday();
            }
            if (edited.startTime() != null) {
                startTime = edited.startTime();
            }
            if (edited.endTime() != null) {
                endTime = edited.endTime();
            }
        }

        // 종료가 자정으로 올림된 경우(예: 23:58 → 24:00)는 하루의 마지막 경계로 붙인다.
        if (endTime.equals(LocalTime.MIDNIGHT) && !startTime.equals(LocalTime.MIDNIGHT)) {
            endTime = LAST_STEP_OF_DAY;
        }
        if (!startTime.isBefore(endTime)) {
            throw invalid("시작이 종료보다 늦거나 같습니다");
        }
        // 🔴 원본 시각은 위에서 floor/ceil 로 5분 격자에 맞췄지만 `edited` 는 사용자가 준 값 그대로다.
        //    여기서 걸러 주지 않으면 09:07 같은 값이 그대로 내려가 ck_fixed_step 에서 터지고,
        //    이 경로엔 DataIntegrityViolationException 을 잡는 곳이 없어 500 이 나간다.
        //    검증은 새로 짜지 않고 정본(FIX-05/06 이 쓰는 것)을 그대로 재사용한다.
        validator.validateTimes(startTime, endTime);

        String title = (edited != null && edited.title() != null && !edited.title().isBlank())
                ? edited.title().trim()
                : event.getTitle();

        return FixedSchedule.createExternal(userId, event.getConnectionId(), title, weekday,
                startTime, endTime, date, date, userClock.now());
    }

    private static void requireEditedTimes(ApplyEventRequest.Edited edited, String reason) {
        if (edited == null || edited.startTime() == null || edited.endTime() == null) {
            throw invalid(reason + " — 시작·종료 시각을 직접 지정해 주세요");
        }
    }

    private static Weekday weekdayOf(ZonedDateTime dateTime) {
        // DayOfWeek(MONDAY..SUNDAY)와 Weekday(MON..SUN)의 순서가 같아 서수로 맞춘다.
        return Weekday.values()[dateTime.getDayOfWeek().getValue() - 1];
    }

    /** 5분 배수로 내림. */
    private static LocalTime floorToStep(LocalTime time) {
        int minutes = time.getHour() * 60 + time.getMinute();
        return LocalTime.ofSecondOfDay((minutes - (minutes % STEP_MINUTES)) * 60L);
    }

    /** 5분 배수로 올림. 24:00 이 되면 호출 측이 하루의 마지막 경계로 붙인다. */
    private static LocalTime ceilToStep(LocalTime time) {
        int minutes = time.getHour() * 60 + time.getMinute();
        if (time.getSecond() > 0 || time.getNano() > 0) {
            minutes += 1;
        }
        int remainder = minutes % STEP_MINUTES;
        int ceiled = (remainder == 0) ? minutes : minutes + (STEP_MINUTES - remainder);
        if (ceiled >= 24 * 60) {
            return LocalTime.MIDNIGHT;   // 호출 측에서 23:55 로 붙는다
        }
        return LocalTime.ofSecondOfDay(ceiled * 60L);
    }

    private static OpenPlanException invalid(String message) {
        return new OpenPlanException(ErrorCode.E_COM_009, Map.of("reason", message), message);
    }
}
