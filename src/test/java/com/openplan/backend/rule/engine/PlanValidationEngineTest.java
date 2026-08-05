package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.RuleId;
import com.openplan.backend.rule.model.Severity;
import com.openplan.backend.rule.model.ValidationIssue;
import com.openplan.backend.rule.model.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증 엔진 골든 — V1(겹침·차단) · V3(가용시간 초과·경고).
 * JSON 골든 하네스(ST-B3-10, 계약 §5)로 확장 예정이며 그 전까지 이 클래스가 정본 기대값을 박제한다.
 */
class PlanValidationEngineTest {

    private final PlanValidationEngine engine = new PlanValidationEngine();

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27); // 월요일
    private static final Instant REF = Instant.parse("2026-07-27T00:00:00Z");

    /**
     * V1 골든용 고정 블록 UUID — A &lt; B &lt; C ({@link UUID#compareTo} 기준).
     * 대표 선정(UUID 오름차순 첫)과 전순서 4번째 키를 박제하려면 임의 UUID로는 안 되고
     * 대소 관계가 통제돼야 한다(계약 §5 · ADR-0013 골든 규약).
     */
    private static final UUID BLOCK_A = UUID.fromString("0000000a-0000-0000-0000-000000000000");
    private static final UUID BLOCK_B = UUID.fromString("0000000b-0000-0000-0000-000000000000");
    private static final UUID BLOCK_C = UUID.fromString("0000000c-0000-0000-0000-000000000000");

    private BlockView blockOnMonday(int startHour, int minutes) {
        return blockOnDay(0, startHour, minutes);
    }

    /** 주 시작(월요일)로부터 {@code plusDays} 일째에 배치된 블록. */
    private BlockView blockOnDay(int plusDays, int startHour, int minutes) {
        Instant start = MONDAY.plusDays(plusDays).atTime(startHour, 0).atZone(ZONE).toInstant();
        Instant end = start.plus(Duration.ofMinutes(minutes));
        return new BlockView(UUID.randomUUID(), BlockType.TASK, UUID.randomUUID(), null, start, end);
    }

    private AvailabilityWindow mondayAvail(int startHour, int endHour) {
        return avail(DayOfWeek.MONDAY, startHour, endHour);
    }

    private AvailabilityWindow avail(DayOfWeek weekday, int startHour, int endHour) {
        return new AvailabilityWindow(weekday, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0), true);
    }

    private AvailabilityWindow inactiveAvail(DayOfWeek weekday, int startHour, int endHour) {
        return new AvailabilityWindow(weekday, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0), false);
    }

    /** 고정 일정에서 파생된 SCHEDULE 블록 (taskId 없음). */
    private BlockView scheduleBlockOnDay(int plusDays, int startHour, int minutes) {
        Instant start = MONDAY.plusDays(plusDays).atTime(startHour, 0).atZone(ZONE).toInstant();
        Instant end = start.plus(Duration.ofMinutes(minutes));
        return new BlockView(UUID.randomUUID(), BlockType.SCHEDULE, null, UUID.randomUUID(), start, end);
    }

    /** 하루 종일 가용 — V1 케이스에서 V3가 섞이지 않도록. */
    private AvailabilityWindow wideMondayAvail() {
        return mondayAvail(9, 18);
    }

    /** 지정 UUID·분 단위 시각의 TASK 블록. */
    private BlockView taskBlock(UUID id, int plusDays, int startHour, int startMinute, int durationMinutes) {
        return blockWith(id, BlockType.TASK, UUID.randomUUID(), null,
                plusDays, startHour, startMinute, durationMinutes);
    }

    /** 지정 UUID의 SCHEDULE 블록 (taskId 없음). */
    private BlockView scheduleBlock(UUID id, int plusDays, int startHour, int startMinute, int durationMinutes) {
        return blockWith(id, BlockType.SCHEDULE, null, UUID.randomUUID(),
                plusDays, startHour, startMinute, durationMinutes);
    }

    private BlockView blockWith(UUID id, BlockType type, UUID taskId, UUID scheduleId,
                                int plusDays, int startHour, int startMinute, int durationMinutes) {
        Instant start = MONDAY.plusDays(plusDays).atTime(startHour, startMinute).atZone(ZONE).toInstant();
        return new BlockView(id, type, taskId, scheduleId, start, start.plus(Duration.ofMinutes(durationMinutes)));
    }

    private PlanSnapshot snapshot(List<BlockView> blocks, List<AvailabilityWindow> avails) {
        return new PlanSnapshot(MONDAY, ZONE, REF, blocks, List.of(), avails, Map.of());
    }

    @Test
    @DisplayName("가용 60분인데 90분 배치 → V3 경고 1건, 저장 가능(차단 0)")
    void 가용시간_초과하면_V3_경고() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10))));

        assertEquals(1, r.issues().size());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(0).ruleId());
        assertEquals(Severity.WARNING, r.issues().get(0).severity());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
        assertTrue(r.savable(), "경고만 있으면 저장 가능");
    }

    @Test
    @DisplayName("가용 120분에 90분 배치 → 위반 없음")
    void 가용시간_이내면_무위반() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 11))));

        assertTrue(r.issues().isEmpty());
        assertTrue(r.savable());
    }

    @Test
    @DisplayName("사유 문구 정본 고정 — 한글 요일 + 가용/배치/초과 수치 (계약 §3.3)")
    void 사유_문구_정본_고정() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10))));

        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 60분 / 배치 90분, 30분 초과)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("초과 요일 2개 → weekday 오름차순(월→수) 고정, 입력 순서 무관 (전순서 마지막 키)")
    void 초과_요일_복수면_요일_오름차순_고정() {
        // 수요일 블록을 먼저 넣어도 출력은 월 → 수 여야 한다 (planBlockId 가 null 이라 weekday 가 유일한 전순서 키)
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnDay(2, 9, 90), blockOnDay(0, 9, 90)),
                List.of(avail(DayOfWeek.WEDNESDAY, 9, 10), mondayAvail(9, 10))));

        assertEquals(2, r.issues().size());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
        assertEquals(DayOfWeek.WEDNESDAY, r.issues().get(1).weekday());
    }

    // ── 경계 정책 (계약 §3.3 "V3 판정 경계", 확정 2026-07-25) ────────────────────

    @Test
    @DisplayName("경계: 배치 총량 == 가용 총량 → 무위반 (판정식이 > 이므로 같으면 통과)")
    void 경계_배치와_가용이_같으면_무위반() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 60)), List.of(mondayAvail(9, 10))));

        assertTrue(r.issues().isEmpty(), "가용을 꽉 채운 계획은 초과가 아니다");
    }

    @Test
    @DisplayName("비활성(active=false) 가용창은 가용 총량에서 제외 → 가용 0분")
    void 비활성_가용창은_총량에서_제외() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 60)), List.of(inactiveAvail(DayOfWeek.MONDAY, 9, 12))));

        assertEquals(1, r.issues().size());
        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 0분 / 배치 60분, 60분 초과)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("가용창이 아예 없는 요일에 배치 → 가용 0분으로 V3 발생 (V4 미구현 상태의 무경고 공백 방지)")
    void 가용창_없는_요일에_배치하면_V3() {
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(9, 60)),
                List.of(avail(DayOfWeek.TUESDAY, 9, 18)))); // 월요일 가용창 없음

        assertEquals(1, r.issues().size());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(0).ruleId());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
    }

    @Test
    @DisplayName("SCHEDULE(고정 일정) 블록도 배치 총량에 합산 — total_planned_minutes 정의와 동일")
    void SCHEDULE_블록도_가용시간을_소비() {
        // 태스크 30분 + 일정 60분 = 90분 > 가용 60분
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(9, 30), scheduleBlockOnDay(0, 14, 60)),
                List.of(mondayAvail(9, 10))));

        assertEquals(1, r.issues().size());
        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 60분 / 배치 90분, 30분 초과)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("자정 넘는 블록은 startAt 요일에 전량 귀속 (V6 '배치일 = zone 기준 startAt 날짜'와 동일)")
    void 자정_교차_블록은_시작_요일에_전량_귀속() {
        // 월 23:00 ~ 화 01:00 (120분) → 화요일로 쪼개지 않고 월요일에 120분 전부
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnDay(0, 23, 120)), List.of(mondayAvail(9, 10))));

        assertEquals(1, r.issues().size(), "요일이 쪼개지면 화요일 이슈까지 2건이 된다");
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 60분 / 배치 120분, 60분 초과)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("동일 입력 → 동일 판정 (P1 결정성)")
    void 동일_입력_동일_판정() {
        PlanSnapshot snap = snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10)));
        assertEquals(engine.validate(snap).issues(), engine.validate(snap).issues());
    }

    // ── V1_OVERLAP (차단) — 계약 §3.3 · ADR-0013 골든 G1~G8 ──────────────────────

    @Test
    @DisplayName("G1: 쌍 1개 겹침 → BLOCK 1건, planBlockId=UUID 첫·counterpartId=나머지, 저장 불가")
    void G1_쌍_하나_겹치면_차단_1건() {
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60), taskBlock(BLOCK_B, 0, 10, 30, 90)),
                List.of(wideMondayAvail())));

        assertEquals(1, r.issues().size(), "겹친 쌍 1개 = 이슈 1건 (ST-B3-01 AC1: 차단 2 = V1+V2)");
        ValidationIssue issue = r.issues().get(0);
        assertEquals(RuleId.V1_OVERLAP, issue.ruleId());
        assertEquals(Severity.BLOCK, issue.severity());
        assertEquals(BLOCK_A, issue.planBlockId());
        assertEquals(BLOCK_B, issue.counterpartId());
        assertNull(issue.taskId(), "쌍 규칙은 판정 축 필드만 채운다");
        assertNull(issue.weekday(), "요일 정보는 reason 이 담는다");
        assertEquals("월요일 10:00~11:00 배치가 10:30~12:00 배치와 겹칩니다. (30분 겹침)", issue.reason());
        assertFalse(r.savable(), "차단이 1건이라도 있으면 저장 불가 (PLAN-28)");
    }

    @Test
    @DisplayName("G2: 정확히 인접(a.end == b.start) → V1 미발생 (경계 = 비겹침, AC3)")
    void G2_정확히_인접하면_미발생() {
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60), taskBlock(BLOCK_B, 0, 11, 0, 60)),
                List.of(wideMondayAvail())));

        assertTrue(r.issues().isEmpty());
        assertTrue(r.savable());
    }

    @Test
    @DisplayName("G3: A가 B·C와 각각 겹침 → planBlockId 동률을 counterpartId 오름차순이 가른다 (입력 순서 무관)")
    void G3_다중_상대는_counterpartId_오름차순() {
        List<BlockView> blocks = List.of(
                taskBlock(BLOCK_A, 0, 9, 0, 180),   // 09:00~12:00
                taskBlock(BLOCK_B, 0, 9, 30, 30),   // 09:30~10:00
                taskBlock(BLOCK_C, 0, 10, 30, 30)); // 10:30~11:00 — B와는 비겹침

        ValidationReport r = engine.validate(snapshot(blocks, List.of(wideMondayAvail())));

        assertEquals(2, r.issues().size());
        assertEquals(BLOCK_A, r.issues().get(0).planBlockId());
        assertEquals(BLOCK_B, r.issues().get(0).counterpartId());
        assertEquals(BLOCK_A, r.issues().get(1).planBlockId());
        assertEquals(BLOCK_C, r.issues().get(1).counterpartId());

        // 입력 순서를 뒤집어도 동일 출력 — 정렬이 입력에 의존하지 않음(P1)
        ValidationReport reversed = engine.validate(snapshot(
                List.of(blocks.get(2), blocks.get(1), blocks.get(0)), List.of(wideMondayAvail())));
        assertEquals(r.issues(), reversed.issues());
    }

    @Test
    @DisplayName("G3b: 상대 둘의 구간이 같아 reason 까지 동일하면 counterpartId 만이 두 이슈를 가른다")
    void G3b_문구가_같아도_counterpartId로_식별된다() {
        // B·C 가 동일 구간이므로 A-B·A-C 의 reason 이 문자 단위로 같다.
        // counterpartId 가 없으면 두 이슈는 구별 불가능한 중복 레코드가 된다 (ADR-0013 ③-1 증명 케이스).
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 9, 0, 180),
                        taskBlock(BLOCK_B, 0, 10, 0, 60),
                        taskBlock(BLOCK_C, 0, 10, 0, 60)),
                List.of(wideMondayAvail())));

        assertEquals(3, r.issues().size(), "A-B · A-C · B-C 3쌍");
        assertEquals(r.issues().get(0).reason(), r.issues().get(1).reason(), "두 이슈의 문구가 동일");
        assertEquals(BLOCK_B, r.issues().get(0).counterpartId());
        assertEquals(BLOCK_C, r.issues().get(1).counterpartId());
    }

    @Test
    @DisplayName("G4: 대표 선정은 UUID 오름차순 — 시간상 늦게 시작해도 UUID 첫이면 planBlockId")
    void G4_대표는_시간이_아니라_UUID로_정해진다() {
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_B, 0, 10, 0, 60),    // 먼저 시작하지만 UUID 는 뒤
                        taskBlock(BLOCK_A, 0, 10, 30, 90)),  // 늦게 시작하지만 UUID 는 앞
                List.of(wideMondayAvail())));

        assertEquals(1, r.issues().size());
        assertEquals(BLOCK_A, r.issues().get(0).planBlockId(), "늦게 시작한 블록이 대표");
        assertEquals(BLOCK_B, r.issues().get(0).counterpartId());
        assertEquals("월요일 10:30~12:00 배치가 10:00~11:00 배치와 겹칩니다. (30분 겹침)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("G5: 자정 교차 쌍 → 상대 블록의 요일이 다르면 구간 앞에 요일 접두")
    void G5_자정_교차_쌍은_상대_요일을_접두한다() {
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 23, 0, 120),   // 월 23:00 ~ 화 01:00
                        taskBlock(BLOCK_B, 1, 0, 30, 60)),   // 화 00:30 ~ 01:30
                List.of(wideMondayAvail(), avail(DayOfWeek.TUESDAY, 0, 23))));

        assertEquals(1, r.issues().size());
        assertEquals("월요일 23:00~01:00 배치가 화요일 00:30~01:30 배치와 겹칩니다. (30분 겹침)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("G6: 3중 상호 겹침 → 병합 없이 쌍 3건, 5키 정렬 고정")
    void G6_3중_상호_겹침은_쌍_3건() {
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 9, 0, 120),    // 09:00~11:00
                        taskBlock(BLOCK_B, 0, 10, 0, 120),   // 10:00~12:00
                        taskBlock(BLOCK_C, 0, 10, 30, 60)),  // 10:30~11:30
                List.of(wideMondayAvail())));

        assertEquals(3, r.issues().size());
        assertEquals(List.of(BLOCK_A, BLOCK_A, BLOCK_B),
                r.issues().stream().map(ValidationIssue::planBlockId).toList());
        assertEquals(List.of(BLOCK_B, BLOCK_C, BLOCK_C),
                r.issues().stream().map(ValidationIssue::counterpartId).toList());
    }

    @Test
    @DisplayName("G7: TASK ↔ SCHEDULE 겹침도 V1 — 블록 타입 무관, taskId 는 채우지 않는다")
    void G7_타입이_달라도_겹치면_V1() {
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60),
                        scheduleBlock(BLOCK_B, 0, 10, 30, 60)),
                List.of(wideMondayAvail())));

        assertEquals(1, r.issues().size());
        assertEquals(RuleId.V1_OVERLAP, r.issues().get(0).ruleId());
        assertNull(r.issues().get(0).taskId());
    }

    @Test
    @DisplayName("G8: 복합(차단 V1 + 경고 V3) → severity 가 1키라 V1 이 선두, 저장 불가")
    void G8_복합이면_차단이_먼저_나온다() {
        // 월요일 배치 150분(60+90) > 가용 60분 → V3 동시 발생
        ValidationReport r = engine.validate(snapshot(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60), taskBlock(BLOCK_B, 0, 10, 30, 90)),
                List.of(mondayAvail(9, 10))));

        assertEquals(2, r.issues().size());
        assertEquals(RuleId.V1_OVERLAP, r.issues().get(0).ruleId());
        assertEquals(Severity.BLOCK, r.issues().get(0).severity());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(1).ruleId());
        assertFalse(r.savable());
    }
}
