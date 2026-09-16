package com.openplan.backend.externalcalendar.outbound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UID 규약 (#69). 🔴 <b>이 규약이 틀리면 되돌릴 수 없다</b> — 우리가 만든 일정을 못 알아보면
 * 다음 동기화가 그것을 새 후보로 들이고, 그것을 또 내보내면서 무한히 늘어난다.
 * 그래서 내보내는 코드보다 이 테스트가 먼저 있다.
 */
class OpenPlanEventUidTest {

    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    @DisplayName("우리가 만든 UID 는 세 종류 전부 우리 것으로 읽힌다")
    void 우리_것을_알아본다() {
        assertThat(OpenPlanEventUid.isOurs(OpenPlanEventUid.forSchedule(A))).isTrue();
        assertThat(OpenPlanEventUid.isOurs(
                OpenPlanEventUid.forFixedOccurrence(A, LocalDate.of(2026, 9, 22)))).isTrue();
        assertThat(OpenPlanEventUid.isOurs(OpenPlanEventUid.forPlanBlock(A, B, 0))).isTrue();
    }

    @Test
    @DisplayName("🔴 남의 UID 를 우리 것으로 읽으면 안 된다 — 남의 일정이 후보에서 조용히 빠진다")
    void 남의_것은_아니라고_답한다() {
        assertThat(OpenPlanEventUid.isOurs("040000008200E00074C5B7101A82E008")).isFalse();  // 아웃룩
        assertThat(OpenPlanEventUid.isOurs("abc123@google.com")).isFalse();
        assertThat(OpenPlanEventUid.isOurs("weekly-tester@icloud.com")).isFalse();
        // 접두사만 흉내 낸 것 · 도메인만 흉내 낸 것 — 둘 다 아니다.
        assertThat(OpenPlanEventUid.isOurs("openplan-schedule-" + A + "@evil.example")).isFalse();
        assertThat(OpenPlanEventUid.isOurs("someone-else@openplan.services")).isFalse();
    }

    @Test
    @DisplayName("null·빈 문자열은 «우리 것 아님» — UID 를 안 주는 제공자가 있다")
    void 없는_값은_남의_것으로_친다() {
        assertThat(OpenPlanEventUid.isOurs(null)).isFalse();
        assertThat(OpenPlanEventUid.isOurs("")).isFalse();
        assertThat(OpenPlanEventUid.isOurs("   ")).isFalse();
    }

    @Test
    @DisplayName("🔴 애플의 합성 식별자(UID#시작시각)로 들어와도 알아본다")
    void 회차_합성본도_알아본다() {
        // AppleCalDavProvider 는 반복을 회차로 펼치며 UID 뒤에 #시작시각을 붙인다.
        // 호출부가 어느 쪽을 넘겼는지 기억해야 하는 규약은 언젠가 틀린다.
        String synthesized = OpenPlanEventUid.forSchedule(A) + "#2026-09-22T01:00:00Z";
        assertThat(OpenPlanEventUid.isOurs(synthesized)).isTrue();
    }

    @Test
    @DisplayName("고정 일정은 회차마다 UID 가 갈린다 — 특정 주만 빼는 것이 «그 일정 삭제»가 되려면")
    void 회차별로_UID가_갈린다() {
        String w1 = OpenPlanEventUid.forFixedOccurrence(A, LocalDate.of(2026, 9, 22));
        String w2 = OpenPlanEventUid.forFixedOccurrence(A, LocalDate.of(2026, 9, 29));
        assertThat(w1).isNotEqualTo(w2);
        assertThat(w1).contains("20260922");
    }

    @Test
    @DisplayName("🔴 블록 UID 는 plan_block_id 를 쓰지 않는다 — 자동 배치가 블록을 새로 만들어도 같아야 한다")
    void 블록_UID는_재배치를_견딘다() {
        // applyBatch 는 블록을 지웠다 새 UUID 로 다시 만든다. plan_block_id 로 UID 를 만들면
        // 재배치 때마다 외부에 새 일정이 생기고 옛 것은 고아로 남는다.
        String before = OpenPlanEventUid.forPlanBlock(A, B, 0);
        String after = OpenPlanEventUid.forPlanBlock(A, B, 0);   // 블록은 새로 만들어졌지만 셋은 같다
        assertThat(after).isEqualTo(before);

        // 한 태스크가 한 주에 둘로 쪼개지면 순번이 가른다.
        assertThat(OpenPlanEventUid.forPlanBlock(A, B, 1)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("재료가 없으면 UID 를 만들지 않는다 — 빈 자리를 채운 UID 는 서로 충돌한다")
    void 재료가_없으면_거부한다() {
        assertThatThrownBy(() -> OpenPlanEventUid.forSchedule(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenPlanEventUid.forFixedOccurrence(A, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenPlanEventUid.forPlanBlock(A, B, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
