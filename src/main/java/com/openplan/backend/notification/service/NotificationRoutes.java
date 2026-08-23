package com.openplan.backend.notification.service;

import java.util.UUID;

/**
 * 알림 클릭 이동 경로 (NOTI-04).
 *
 * <p>🔴 <b>값의 출처는 설계 문서가 아니라 실제 {@code router.js} 다.</b> ADR-0014 는 경로를
 * "ux-flow §0 라우트 표와 1:1"로 채우라고 했으나, 2026-08-18 실측에서 그 표가 낡아 있었다
 * ({@code /plan}·{@code /stats} 는 앱에 없다 — 표대로 채웠으면 알림마다 404 로 갔을 것이다).
 * 표는 같은 날 정정했고, 여기 값은 {@code OpenPlan-Frontend} {@code 6c8a89b} 기준이다.
 *
 * <p>{@code NotificationBell.jsx} 가 이 문자열을 {@code navigate()} 에 그대로 넘긴다 —
 * <b>화면 코드가 아니라 라우터가 아는 경로여야 한다.</b>
 */
final class NotificationRoutes {

    private NotificationRoutes() {
    }

    /**
     * 마감 임박 → 해당 태스크를 연다.
     *
     * <p>🔴 태스크 편집은 라우트가 아니라 모달({@code TaskEditModal})이라 딥링크할 주소가 없었다.
     * 사용자 확정(2026-08-18)으로 <b>주간 화면의 쿼리 파라미터</b>로 연다 — {@code ?project=}·
     * {@code ?openUnplaced=}·{@code ?openReplan=} 과 같은 기존 관례다.
     * <b>FE 측 파라미터 처리가 붙기 전까지는 주간 화면까지만 열린다</b>(깨지지는 않는다).
     */
    static String task(UUID taskId) {
        return "/weekly?task=" + taskId;
    }

    /** 오늘 할 일 · 미저장 계획 → 주간 계획. */
    static String weekly() {
        return "/weekly";
    }

    /** 회고 → 수행 통계(회고의 MVP1 구현 표면 — ADR-0014). */
    static String statistics() {
        return "/statistics";
    }

    /** 답변 등록 → 본인 문의 상세만(NFR-030). */
    static String supportTicket(UUID ticketId) {
        return "/help/" + ticketId;
    }
}
