package com.openplan.backend.externalcalendar.outbound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 태스크 블록 매핑 (#69 5단계).
 *
 * <p>🔴 이 매핑의 존재 이유는 <b>재배치를 견디는 것</b>이다. 자동 배치는 블록을 지웠다 새 UUID 로
 * 다시 만들고, 주차 이동은 주간계획만 바꾼다 — 그때마다 외부에 새 일정이 생기면 사용자 캘린더가
 * 고아로 가득 찬다.
 */
class PlanBlockExternalRefTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONN = UUID.randomUUID();
    private static final UUID PLAN_A = UUID.randomUUID();
    private static final UUID PLAN_B = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final Instant START = Instant.parse("2026-09-22T05:00:00Z");
    private static final Instant END = Instant.parse("2026-09-22T06:00:00Z");

    @Test
    @DisplayName("🔴 주차 이동은 UID 를 바꾸지 않는다 — 외부에는 수정 한 번이 나간다")
    void 주차_이동에도_UID는_그대로다() {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);
        String uid = ref.getExternalUid();

        ref.movedTo(PLAN_B, NOW);

        // UID 를 weeklyPlanId 에서 파생했다면 여기서 값이 달라져, 외부에는 옛 UID 의 일정이
        // 고아로 남고 새 일정이 또 생긴다 — #76 리뷰가 잡은 바로 그 결함이다.
        assertThat(ref.getExternalUid()).isEqualTo(uid);
        assertThat(ref.getWeeklyPlanId()).isEqualTo(PLAN_B);
    }

    @Test
    @DisplayName("발급받은 UID 는 우리 것으로 읽힌다")
    void 발급받은_UID는_우리_것이다() {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);

        assertThat(OpenPlanEventUid.isOurs(ref.getExternalUid())).isTrue();
    }

    @Test
    @DisplayName("같은 태스크의 다른 세션은 다른 UID 를 받는다")
    void 세션마다_UID가_갈린다() {
        PlanBlockExternalRef first = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);
        PlanBlockExternalRef second = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 1, NOW);

        assertThat(first.getExternalUid()).isNotEqualTo(second.getExternalUid());
    }

    @Test
    @DisplayName("한 번도 안 보냈으면 CREATE 대상이다")
    void 안_보냈으면_생성이다() {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);

        assertThat(ref.neverSent()).isTrue();
    }

    @Test
    @DisplayName("🔴 보낸 그대로면 다시 보내지 않는다 — 확정할 때마다 같은 것을 또 쓰면 안 된다")
    void 보낸_그대로면_다시_보내지_않는다() {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);
        ref.recordSent("evt-1", null, "\"e1\"", "알고리즘", START, END, NOW);

        assertThat(ref.neverSent()).isFalse();
        assertThat(ref.differsFrom("알고리즘", START, END)).isFalse();
        assertThat(ref.differsFrom("알고리즘", START.plusSeconds(3600), END)).isTrue();
    }

    @Test
    @DisplayName("🔴 ETag 는 값이 왔을 때만 갱신한다 — 지우면 다음 수정이 If-Match 없이 나간다")
    void ETag는_지워지지_않는다() {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);
        ref.recordSent("evt-1", null, "\"e1\"", "알고리즘", START, END, NOW);

        ref.recordSent("evt-1", null, null, "알고리즘", START, END, NOW);

        assertThat(ref.getEtag()).isEqualTo("\"e1\"");
    }

    @Test
    @DisplayName("아직 안 보낸 것은 «창 안» 이 아니다 — 보내지도 않은 것을 외부에 없다고 지우면 안 된다")
    void 안_보낸_것은_창_안이_아니다() {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, CONN, PLAN_A, TASK, 0, NOW);

        assertThat(ref.wasSentWithin(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z"))).isFalse();
    }
}
