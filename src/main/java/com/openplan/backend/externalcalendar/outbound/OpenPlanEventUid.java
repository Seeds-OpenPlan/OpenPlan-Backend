package com.openplan.backend.externalcalendar.outbound;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * OpenPlan 이 외부 캘린더에 만든 일정의 UID 규약 (이슈 #69).
 *
 * <p><b>왜 규약이 먼저인가.</b> 우리가 구글·애플에 만든 일정은 <b>다음 동기화 때 그대로 읽혀 온다.</b>
 * 막지 않으면 「내가 만든 일정」이 「외부에서 온 새 후보」로 다시 들어오고, 그것을 또 내보내면
 * 무한히 늘어난다. 🔴 <b>이미 중복이 쌓인 뒤에는 되돌릴 수 없으므로</b> 내보내는 코드가 서기 전에
 * 규약을 고정해 둔다.
 *
 * <p><b>두 가지를 분리한다</b>(계획 D4). 우리 UID 를 만났을 때 —
 * <ul>
 *   <li><b>새 후보로 만들지 않는다</b> — 이것이 에코 차단이다.</li>
 *   <li><b>변경은 본다</b> — 내보낼 때 기록한 {@code ETag} 와 달라졌으면 사용자가 고친 것이다.
 *       「우리 것이니 무시한다」가 아니라 「우리 것이니 새로 만들지는 않는다」다.</li>
 * </ul>
 *
 * <p><b>블록 UID 에 식별자 셋을 넣는 이유.</b> 자동 배치는 블록을 지웠다 새 UUID 로 다시 만든다
 * ({@code PlanBlockService.applyBatch}). {@code plan_block_id} 를 UID 에 쓰면 그때마다 외부에
 * 새 일정이 생기고 옛 것은 고아로 남는다. (주간계획, 태스크, 순번)은 재배치를 견딘다.
 *
 * <p>도메인 부분은 iCalendar 관례를 따른다(RFC 5545 §3.8.4.7 — 전역 유일성).
 */
public final class OpenPlanEventUid {

    /** 우리 것임을 알아보는 표식. 이 접두사로 시작하는 UID 는 OpenPlan 이 만든 것이다. */
    private static final String PREFIX = "openplan-";
    private static final String DOMAIN = "@openplan.services";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private OpenPlanEventUid() {
    }

    /** 개인 일정 — 일회성이라 외부 일정과 1:1. */
    public static String forSchedule(UUID scheduleId) {
        return PREFIX + "schedule-" + require(scheduleId, "scheduleId") + DOMAIN;
    }

    /**
     * 고정 일정의 한 회차 — 2개월치를 개별 일정으로 펼쳐 넣는다(계획 D1).
     *
     * <p>회차마다 UID 가 갈려야 특정 주만 빼는 기능(주차 예외)이 «그 일정 하나 삭제»가 된다.
     * 하나의 반복 일정으로 넣으면 그것이 {@code EXDATE} 편집이 되고, 애플에서는 파일을 통째로
     * 덮는 연산이라 다른 회차까지 위험해진다.
     */
    public static String forFixedOccurrence(UUID fixedScheduleId, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date 가 없으면 회차를 가릴 수 없다");
        }
        return PREFIX + "fixed-" + require(fixedScheduleId, "fixedScheduleId") + "-" + DATE.format(date) + DOMAIN;
    }

    /**
     * 태스크 블록 — 키가 {@code plan_block_id} 가 아니다(위 클래스 주석 참조).
     *
     * @param sequence 한 태스크가 한 주에 여러 블록으로 쪼개질 수 있어 순번이 필요하다. 0부터.
     */
    public static String forPlanBlock(UUID weeklyPlanId, UUID taskId, int sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence 는 음수일 수 없다: " + sequence);
        }
        return PREFIX + "block-" + require(weeklyPlanId, "weeklyPlanId")
                + "-" + require(taskId, "taskId") + "-" + sequence + DOMAIN;
    }

    /**
     * 이 UID 는 OpenPlan 이 만든 것인가.
     *
     * <p><b>무엇을 넣어야 하는가.</b> 제공자마다 자리가 다르다 —
     * 애플(CalDAV)은 {@code .ics} 의 {@code UID}, 구글은 {@code id} 가 아니라 <b>{@code iCalUID}</b> 다.
     * {@code id} 를 넣으면 우리 것을 알아보지 못해 에코가 그대로 돈다.
     *
     * <p>애플 경로는 반복을 회차로 펼치면서 {@code UID#시작시각} 으로 식별자를 합성한다
     * ({@code AppleCalDavProvider}). 그 합성본이 그대로 들어와도 알아보도록 {@code #} 뒤를 떼고 본다 —
     * 호출부가 어느 쪽을 넘겼는지 기억해야 하는 규약은 언젠가 틀린다.
     *
     * <p>null·빈 문자열은 «우리 것이 아니다»로 답한다. 외부 제공자가 UID 를 안 주는 경우가 있고,
     * 그때 우리 것으로 오인하면 <b>남의 일정을 후보에서 조용히 빼 버린다.</b>
     */
    public static boolean isOurs(String rawUid) {
        if (rawUid == null || rawUid.isBlank()) {
            return false;
        }
        int hash = rawUid.indexOf('#');
        String uid = hash < 0 ? rawUid : rawUid.substring(0, hash);
        return uid.startsWith(PREFIX) && uid.endsWith(DOMAIN);
    }

    private static UUID require(UUID id, String name) {
        if (id == null) {
            throw new IllegalArgumentException(name + " 이(가) 없으면 UID 를 만들 수 없다");
        }
        return id;
    }
}
