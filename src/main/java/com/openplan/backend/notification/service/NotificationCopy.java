package com.openplan.backend.notification.service;

import java.time.LocalDate;

/**
 * 알림 문구 (ui-spec 「알림 카피 표」 — Jonnathan 확정 2026-08-18).
 *
 * <p><b>여기 문자열은 디자인 산출물이다.</b> 임의로 고치지 말고 ui-spec 을 먼저 고쳐라.
 *
 * <p>확정 과정에서 초안 2건이 바뀌었고, 이유가 구현 제약이라 남겨 둔다.
 * <ul>
 *   <li><b>DEADLINE_SOON 은 상대 일수가 아니라 절대 날짜다.</b> 초안 {@code "{n}일 남았습니다"}는
 *       알림을 하루만 늦게 열어도 문장이 거짓이 되고(title 은 박제라 갱신되지 않는다),
 *       당일 마감이면 "0일 남았습니다"라는 비문이 된다. {@code m/d}는 언제 읽어도 참이다.</li>
 *   <li><b>RETROSPECT 는 권유가 아니라 서술이다.</b> 초안 {@code "…돌아보세요"}는 명령형이라
 *       P4(판정은 규칙 — 조언하지 않는다)를 어긴다. 이동은 문구가 아니라 routePath 가 한다.</li>
 * </ul>
 */
final class NotificationCopy {

    /** 제목 인용 상한 — 알림 행이 좁아(400px) 뒤 고정 문구가 밀려 잘리는 것을 막는다(ui-spec). */
    private static final int TITLE_LIMIT = 20;

    private NotificationCopy() {
    }

    static String deadlineSoon(String taskTitle, LocalDate dueDate) {
        return "'" + ellipsis(taskTitle) + "' 마감이 "
                + dueDate.getMonthValue() + "/" + dueDate.getDayOfMonth() + "로 임박했습니다";
    }

    static String todayTasks(int count) {
        return "오늘 할 일 " + count + "건이 있습니다";
    }

    static String planUnsaved() {
        return "이번 주 계획이 저장되지 않았습니다";
    }

    static String retrospect(int count) {
        return "지난주 수행 기록이 " + count + "건 있습니다";
    }

    static String supportAnswered(String ticketTitle) {
        return "'" + ellipsis(ticketTitle) + "' 문의에 답변이 등록되었습니다";
    }

    /** 20자 초과 시 자르고 말줄임 부착(ui-spec 플레이스홀더 규칙). */
    private static String ellipsis(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= TITLE_LIMIT ? raw : raw.substring(0, TITLE_LIMIT) + "…";
    }
}
