package com.openplan.backend.rule.engine;

import com.openplan.backend.rule.model.AvailabilityWindow;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.RuleId;
import com.openplan.backend.rule.model.Severity;
import com.openplan.backend.rule.model.TaskFacts;
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
 * 검증 엔진 골든 — V1(겹침·차단) · V2(고정 일정 충돌·차단) · V3(가용시간 초과·경고).
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

        // V4 도입 후 2건 — 창(09~10) 밖으로 나간 배치라 "총량 초과(V3)"와 "창 밖(V4)"이 함께 성립한다.
        assertEquals(2, r.issues().size());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(0).ruleId());
        assertEquals(RuleId.V4_OUT_OF_AVAILABILITY, r.issues().get(1).ruleId());
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

        // V3 2건(월·수) + V4 2건(각 블록이 창 밖) = 4건. ruleId 가 2키라 V3 둘이 앞선다.
        assertEquals(4, r.issues().size());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(0).ruleId());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(1).ruleId());
        assertEquals(DayOfWeek.WEDNESDAY, r.issues().get(1).weekday());
    }

    // ── 경계 정책 (계약 §3.3 "V3 판정 경계", 확정 2026-07-25) ────────────────────

    @Test
    @DisplayName("경계: 배치 총량 == 가용 총량 → V3 아님(> 이므로), 대신 V7 (버퍼 0%)")
    void 경계_배치와_가용이_같으면_V3_아니라_V7() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 60)), List.of(mondayAvail(9, 10))));

        // V3는 여전히 미발생 — 판정식이 > 라 같으면 초과가 아니다(기존 경계 유지).
        // V7 도입 후: buffer=0 이고 0×100 < 60×10 이라 "여유 없음"으로 잡힌다.
        // 두 규칙은 상호 배타이며 여기서 갈리는 지점이 정확히 이 경계다.
        assertEquals(1, r.issues().size());
        assertEquals(RuleId.V7_BUFFER_SHORTAGE, r.issues().get(0).ruleId());
        assertEquals(Severity.WARNING, r.issues().get(0).severity());
        assertTrue(r.savable());
    }

    @Test
    @DisplayName("비활성(active=false) 가용창은 가용 총량에서 제외 → 가용 0분")
    void 비활성_가용창은_총량에서_제외() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 60)), List.of(inactiveAvail(DayOfWeek.MONDAY, 9, 12))));

        // 활성 창이 0개라 V4 도 함께 — 담을 창 자체가 없다. V7 은 미발생(가용 0인 요일은 순회 대상 아님).
        assertEquals(2, r.issues().size());
        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 0분 / 배치 60분, 60분 초과)",
                r.issues().get(0).reason());
        assertEquals(RuleId.V4_OUT_OF_AVAILABILITY, r.issues().get(1).ruleId());
        assertEquals("월요일에는 가용 시간이 없습니다. (09:00~10:00 배치)", r.issues().get(1).reason());
    }

    @Test
    @DisplayName("가용창이 아예 없는 요일에 배치 → V3(가용 0분) + V4(담을 창 없음)")
    void 가용창_없는_요일에_배치하면_V3() {
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(9, 60)),
                List.of(avail(DayOfWeek.TUESDAY, 9, 18)))); // 월요일 가용창 없음

        // 계약 §3.3 경계 c 의 근거였던 "V4 미구현이라 V3 로 공백을 막는다"가 해소된 지점.
        // 계약은 V4 구현 후에도 V3 억제가 아니라 FE 표시 계층 병합을 먼저 검토하라고 했으므로 둘 다 낸다.
        assertEquals(2, r.issues().size());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(0).ruleId());
        assertEquals(RuleId.V4_OUT_OF_AVAILABILITY, r.issues().get(1).ruleId());
        assertEquals(DayOfWeek.MONDAY, r.issues().get(0).weekday());
    }

    @Test
    @DisplayName("SCHEDULE(고정 일정) 블록도 배치 총량에 합산 — total_planned_minutes 정의와 동일")
    void SCHEDULE_블록도_가용시간을_소비() {
        // 태스크 30분 + 일정 60분 = 90분 > 가용 60분
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(9, 30), scheduleBlockOnDay(0, 14, 60)),
                List.of(mondayAvail(9, 10))));

        // 14시 블록이 09~10 창 밖이라 V4 가 함께 나온다(09:00 블록은 창 안이라 V4 없음).
        assertEquals(2, r.issues().size());
        assertEquals("월요일 배치 시간이 가용 시간을 초과했습니다. (가용 60분 / 배치 90분, 30분 초과)",
                r.issues().get(0).reason());
        assertEquals(RuleId.V4_OUT_OF_AVAILABILITY, r.issues().get(1).ruleId());
    }

    @Test
    @DisplayName("자정 넘는 블록은 startAt 요일에 전량 귀속 (V6 '배치일 = zone 기준 startAt 날짜'와 동일)")
    void 자정_교차_블록은_시작_요일에_전량_귀속() {
        // 월 23:00 ~ 화 01:00 (120분) → 화요일로 쪼개지 않고 월요일에 120분 전부
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnDay(0, 23, 120)), List.of(mondayAvail(9, 10))));

        // V3 + V4. 자정을 넘는 블록은 담을 창이 존재할 수 없어(창은 startTime<endTime) 항상 V4 다.
        // 핵심 단언은 그대로 — 두 이슈 모두 월요일이며 화요일 이슈가 생기지 않는다.
        assertEquals(2, r.issues().size());
        assertTrue(r.issues().stream().allMatch(i -> i.weekday() == DayOfWeek.MONDAY),
                "요일이 쪼개지면 화요일 이슈가 생긴다");
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

        // 자정을 넘는 A 블록에 V4 가 함께 붙는다(담을 창이 존재할 수 없음). 이 테스트의 대상은 V1 문구다.
        assertEquals(2, r.issues().size());
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

        // V1 + V3 + V4 2건(두 블록 모두 09~10 창 밖) = 4건. 선두가 차단이라는 것이 이 테스트의 핵심이다.
        assertEquals(4, r.issues().size());
        assertEquals(RuleId.V1_OVERLAP, r.issues().get(0).ruleId());
        assertEquals(Severity.BLOCK, r.issues().get(0).severity());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(1).ruleId());
        assertFalse(r.savable());
    }

    // ───────────────────────── V2 고정 일정 충돌 (계약 §3.3 V2) ─────────────────────────

    /**
     * V2 골든용 고정 일정 UUID — F1 &lt; F2. 한 블록이 두 창과 겹칠 때 전순서 4번째 키
     * ({@code counterpartId}) 가 가르는 것을 박제하려면 대소 관계가 통제돼야 한다.
     */
    private static final UUID FIXED_1 = UUID.fromString("000000f1-0000-0000-0000-000000000000");
    private static final UUID FIXED_2 = UUID.fromString("000000f2-0000-0000-0000-000000000000");

    /** 무기한 고정 창 (effectiveFrom/To 둘 다 null — DB 상 허용된 기본형). */
    private FixedWindow fixedWindow(UUID id, DayOfWeek weekday, int startHour, int endHour) {
        return new FixedWindow(id, weekday, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0), null, null);
    }

    private PlanSnapshot snapshotWithFixed(List<BlockView> blocks, List<FixedWindow> fixed,
                                           List<AvailabilityWindow> avails) {
        return new PlanSnapshot(MONDAY, ZONE, REF, blocks, fixed, avails, Map.of());
    }

    @Test
    @DisplayName("F1: 블록이 고정 창과 겹침 → V2 BLOCK 1건, planBlockId=블록·counterpartId=fixedScheduleId, 저장 불가")
    void F1_고정창과_겹치면_V2() {
        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60)),      // 월 10:00~11:00
                List.of(fixedWindow(FIXED_1, DayOfWeek.MONDAY, 10, 12)),
                List.of(wideMondayAvail())));

        assertEquals(1, r.issues().size());
        ValidationIssue issue = r.issues().get(0);
        assertEquals(RuleId.V2_FIXED_CONFLICT, issue.ruleId());
        assertEquals(Severity.BLOCK, issue.severity());
        assertEquals(BLOCK_A, issue.planBlockId());
        assertEquals(FIXED_1, issue.counterpartId());
        assertNull(issue.taskId());   // 쌍 규칙은 판정 축 필드만 (계약 §3.3)
        assertNull(issue.weekday());
        assertFalse(r.savable());
    }

    @Test
    @DisplayName("F2: 정확히 인접(블록 end == 창 start) → V2 미발생 (V1과 동일 교차식, 경계는 비겹침)")
    void F2_인접은_겹침이_아니다() {
        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 9, 0, 60)),       // 월 09:00~10:00
                List.of(fixedWindow(FIXED_1, DayOfWeek.MONDAY, 10, 12)),
                List.of(wideMondayAvail())));

        assertTrue(r.issues().isEmpty());
        assertTrue(r.savable());
    }

    @Test
    @DisplayName("F3: 요일이 다른 창은 전개 날짜가 달라 겹치지 않는다")
    void F3_다른_요일_창은_무관() {
        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60)),      // 월요일 배치
                List.of(fixedWindow(FIXED_1, DayOfWeek.TUESDAY, 10, 12)),
                List.of(wideMondayAvail())));

        assertTrue(r.issues().isEmpty());
    }

    @Test
    @DisplayName("F4: 사유 문구 정본 고정 — 상대 표기가 '배치'가 아니라 '고정 일정' (V1과 구분)")
    void F4_사유_문구_정본() {
        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60)),
                List.of(fixedWindow(FIXED_1, DayOfWeek.MONDAY, 10, 12)),
                List.of(wideMondayAvail())));

        assertEquals("월요일 10:00~11:00 배치가 10:00~12:00 고정 일정과 겹칩니다. (60분 겹침)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("F5: 한 블록이 두 창과 겹침 → 쌍 2건, counterpartId 오름차순 (입력 순서 무관)")
    void F5_다중_상대는_counterpartId가_가른다() {
        ValidationReport reversed = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 120)),     // 월 10:00~12:00
                List.of(fixedWindow(FIXED_2, DayOfWeek.MONDAY, 11, 13),
                        fixedWindow(FIXED_1, DayOfWeek.MONDAY, 9, 11)),  // 역순 입력
                List.of(wideMondayAvail())));

        assertEquals(2, reversed.issues().size());
        assertEquals(FIXED_1, reversed.issues().get(0).counterpartId());
        assertEquals(FIXED_2, reversed.issues().get(1).counterpartId());
    }

    @Test
    @DisplayName("F6: effectiveTo 가 그 주 이전이면 제외 — 만료된 고정 일정은 판정하지 않는다")
    void F6_유효기간_밖은_제외() {
        FixedWindow expired = new FixedWindow(FIXED_1, DayOfWeek.MONDAY,
                LocalTime.of(10, 0), LocalTime.of(12, 0),
                null, MONDAY.minusDays(1));   // 그 주 월요일 하루 전에 종료

        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60)), List.of(expired),
                List.of(wideMondayAvail())));

        assertTrue(r.issues().isEmpty());
    }

    @Test
    @DisplayName("F7: effectiveFrom 이 그 주 이후면 제외 — 아직 시작 안 한 고정 일정")
    void F7_시작전_고정일정은_제외() {
        FixedWindow future = new FixedWindow(FIXED_1, DayOfWeek.MONDAY,
                LocalTime.of(10, 0), LocalTime.of(12, 0),
                MONDAY.plusDays(7), null);

        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60)), List.of(future),
                List.of(wideMondayAvail())));

        assertTrue(r.issues().isEmpty());
    }

    @Test
    @DisplayName("F8: 경계 — 전개 날짜가 effectiveFrom/To 와 정확히 같으면 유효 (범위 포함)")
    void F8_유효기간_경계는_포함() {
        FixedWindow exact = new FixedWindow(FIXED_1, DayOfWeek.MONDAY,
                LocalTime.of(10, 0), LocalTime.of(12, 0), MONDAY, MONDAY);

        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60)), List.of(exact),
                List.of(wideMondayAvail())));

        assertEquals(1, r.issues().size());
        assertEquals(RuleId.V2_FIXED_CONFLICT, r.issues().get(0).ruleId());
    }

    /**
     * 계약 §5 필수 케이스 — <b>주차 예외 쌍</b>.
     * 같은 배치를 두 스냅샷에 주되 창의 유무만 바꾼다. 예외가 적용된 주는 조립이 창을 빼므로
     * V2 가 안 나오고, 예외 없는 주는 나온다 → <b>미발생 = 그 주 한정 비활성</b> 의미론이 고정된다(B12).
     * 엔진은 예외 자체를 모른다는 사실을 이 쌍이 증명한다.
     */
    @Test
    @DisplayName("F9(계약 §5 필수): 주차 예외 쌍 — 같은 배치라도 창이 빠진 주는 V2 미발생")
    void F9_주차예외_쌍_케이스() {
        List<BlockView> samePlacement = List.of(taskBlock(BLOCK_A, 0, 10, 0, 60));
        FixedWindow window = fixedWindow(FIXED_1, DayOfWeek.MONDAY, 10, 12);

        // 예외가 적용된 주 — 조립(BE-2)이 창을 제외해 입력에 없다
        ValidationReport excepted = engine.validate(snapshotWithFixed(
                samePlacement, List.of(), List.of(wideMondayAvail())));

        // 예외 없는 주 — 같은 배치인데 V2 가 난다
        ValidationReport normal = engine.validate(snapshotWithFixed(
                samePlacement, List.of(window), List.of(wideMondayAvail())));

        assertTrue(excepted.issues().isEmpty());
        assertTrue(excepted.savable());
        assertEquals(1, normal.issues().size());
        assertEquals(RuleId.V2_FIXED_CONFLICT, normal.issues().get(0).ruleId());
        assertFalse(normal.savable());
    }

    @Test
    @DisplayName("F10: 자정 넘는 블록이 다음날 창과 겹침 → 창 요일이 블록 요일과 달라 요일 접두가 붙는다")
    void F10_자정교차_블록은_창_요일_접두() {
        // 월 23:00 ~ 화 01:00 배치 vs 화요일 00:00~02:00 창
        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 23, 0, 120)),
                List.of(fixedWindow(FIXED_1, DayOfWeek.TUESDAY, 0, 2)),
                // 자정 넘는 블록 120분은 전량 startAt 요일(월)에 귀속되므로 월 가용만 넉넉하면 V3 미발생
                List.of(wideMondayAvail())));

        // 자정을 넘는 블록이라 V4 가 함께 붙는다. 이 테스트의 대상은 V2 문구다.
        assertEquals(2, r.issues().size());
        assertEquals("월요일 23:00~01:00 배치가 화요일 00:00~02:00 고정 일정과 겹칩니다. (60분 겹침)",
                r.issues().get(0).reason());
    }

    @Test
    @DisplayName("F11: 복합(V1 + V2 + V3) → ruleId 가 2키라 V1 → V2 → V3 순, 저장 불가")
    void F11_복합_정렬() {
        // 월 10:00~11:00(A) 과 10:30~12:00(B) 이 서로 겹치고(V1), 둘 다 10:00~12:00 창과 겹치며(V2 2건),
        // 배치 총량 150분 > 가용 60분(V3)
        ValidationReport r = engine.validate(snapshotWithFixed(
                List.of(taskBlock(BLOCK_A, 0, 10, 0, 60), taskBlock(BLOCK_B, 0, 10, 30, 90)),
                List.of(fixedWindow(FIXED_1, DayOfWeek.MONDAY, 10, 12)),
                List.of(mondayAvail(9, 10))));

        // V1 1 + V2 2 + V3 1 + V4 2(두 블록 모두 09~10 창 밖) = 6. 순서(V1→V2→V3→V4)가 이 테스트의 대상이다.
        assertEquals(6, r.issues().size());
        assertEquals(RuleId.V1_OVERLAP, r.issues().get(0).ruleId());
        assertEquals(RuleId.V2_FIXED_CONFLICT, r.issues().get(1).ruleId());
        assertEquals(BLOCK_A, r.issues().get(1).planBlockId());   // planBlockId 오름차순
        assertEquals(RuleId.V2_FIXED_CONFLICT, r.issues().get(2).ruleId());
        assertEquals(BLOCK_B, r.issues().get(2).planBlockId());
        assertEquals(RuleId.V3_CAPACITY_EXCEEDED, r.issues().get(3).ruleId());
        assertFalse(r.savable());
    }

    @Test
    @DisplayName("F12: 동일 입력 → 동일 판정 (P1 결정성, 창 입력 순서 무관)")
    void F12_결정성() {
        List<BlockView> blocks = List.of(taskBlock(BLOCK_A, 0, 10, 0, 120));
        List<AvailabilityWindow> avails = List.of(wideMondayAvail());
        FixedWindow w1 = fixedWindow(FIXED_1, DayOfWeek.MONDAY, 9, 11);
        FixedWindow w2 = fixedWindow(FIXED_2, DayOfWeek.MONDAY, 11, 13);

        ValidationReport first = engine.validate(snapshotWithFixed(blocks, List.of(w1, w2), avails));
        ValidationReport second = engine.validate(snapshotWithFixed(blocks, List.of(w2, w1), avails));

        assertEquals(first.issues().size(), second.issues().size());
        for (int i = 0; i < first.issues().size(); i++) {
            assertEquals(first.issues().get(i).ruleId(), second.issues().get(i).ruleId());
            assertEquals(first.issues().get(i).planBlockId(), second.issues().get(i).planBlockId());
            assertEquals(first.issues().get(i).counterpartId(), second.issues().get(i).counterpartId());
            assertEquals(first.issues().get(i).reason(), second.issues().get(i).reason());
        }
    }

    // ═══════════════════ V4 가용 시간 밖 (계약 §3.3 V4) ═══════════════════

    private static final UUID TASK_1 = UUID.fromString("00000001-0000-0000-0000-000000000000");

    /** 지정 taskId 의 TASK 블록 — taskFacts 와 연결하기 위해 랜덤 taskId 를 쓰지 않는다. */
    private BlockView taskBlockOf(UUID blockId, UUID taskId, int plusDays, int startHour, int durationMinutes) {
        return blockWith(blockId, BlockType.TASK, taskId, null, plusDays, startHour, 0, durationMinutes);
    }

    private PlanSnapshot snapshotWithFacts(List<BlockView> blocks, List<AvailabilityWindow> avails,
                                           Map<UUID, TaskFacts> facts) {
        return new PlanSnapshot(MONDAY, ZONE, REF, blocks, List.of(), avails, facts);
    }

    /** 하루를 넉넉히 여는 창 — V4·V7 노이즈 없이 V5·V6 만 보기 위한 배경. */
    private AvailabilityWindow allDayMonday() {
        return avail(DayOfWeek.MONDAY, 0, 23);
    }

    @Test
    @DisplayName("V4: 창 안에 완전히 포함되면 무위반")
    void V4_창_안이면_무위반() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(10, 60)), List.of(mondayAvail(9, 18))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V4_OUT_OF_AVAILABILITY));
    }

    @Test
    @DisplayName("V4: 끝이 창을 넘으면 위반 — 부분 포함은 통과가 아니다")
    void V4_끝이_창을_넘으면_위반() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(17, 120)), List.of(mondayAvail(9, 18))));

        assertTrue(r.issues().stream().anyMatch(i -> i.ruleId() == RuleId.V4_OUT_OF_AVAILABILITY));
    }

    @Test
    @DisplayName("V4: 창이 인접해 있으면 합집합으로 본다 — 09~12 + 12~18 에 11~13 배치는 무위반")
    void V4_인접한_창은_이어진_것으로_본다() {
        // 창 하나하나와 비교하면 어느 창에도 완전히 안 들어가 위반이 되지만,
        // 실제로는 쓸 수 없는 시간이 사이에 없다.
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(11, 120)),
                List.of(mondayAvail(9, 12), mondayAvail(12, 18))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V4_OUT_OF_AVAILABILITY));
    }

    @Test
    @DisplayName("V4: 창 사이에 빈틈이 있으면 위반 — 09~11 + 13~18 에 10~14 배치")
    void V4_창_사이_빈틈은_위반() {
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(10, 240)),
                List.of(mondayAvail(9, 11), mondayAvail(13, 18))));

        assertTrue(r.issues().stream().anyMatch(i -> i.ruleId() == RuleId.V4_OUT_OF_AVAILABILITY));
    }

    @Test
    @DisplayName("V4: 사유 문구 정본 — 가용 창을 시작 시각 오름차순으로 잇는다")
    void V4_사유_문구_정본() {
        // 입력 순서를 뒤집어 넣어도 문구의 창 순서는 09~11 → 13~18 이어야 한다(P1).
        ValidationReport r = engine.validate(snapshot(
                List.of(blockOnMonday(10, 240)),
                List.of(mondayAvail(13, 18), mondayAvail(9, 11))));

        ValidationIssue v4 = r.issues().stream()
                .filter(i -> i.ruleId() == RuleId.V4_OUT_OF_AVAILABILITY).findFirst().orElseThrow();
        assertEquals("월요일 10:00~14:00 배치가 가용 시간 밖입니다. (가용 09:00~11:00, 13:00~18:00)",
                v4.reason());
    }

    // ═══════════════════ V5 WBS 기간 밖 (계약 §3.3 V5) ═══════════════════

    @Test
    @DisplayName("V5: WBS 기간 안이면 무위반")
    void V5_WBS_안이면_무위반() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(null, MONDAY.minusDays(1), MONDAY.plusDays(3), 60, 1))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V5_OUT_OF_WBS));
    }

    @Test
    @DisplayName("V5: WBS 기간보다 뒤에 배치되면 위반 + 문구 정본")
    void V5_WBS_밖이면_위반() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(null, MONDAY.minusDays(5), MONDAY.minusDays(1), 60, 1))));

        ValidationIssue v5 = r.issues().stream()
                .filter(i -> i.ruleId() == RuleId.V5_OUT_OF_WBS).findFirst().orElseThrow();
        assertEquals(Severity.WARNING, v5.severity());
        assertEquals(BLOCK_A, v5.planBlockId());
        assertEquals(TASK_1, v5.taskId());
        assertEquals("월요일 배치가 WBS 기간 밖입니다. (WBS 2026-07-22~2026-07-26, 배치 2026-07-27)",
                v5.reason());
    }

    @Test
    @DisplayName("V5: WBS 미설정 태스크는 판정 제외 — 기능을 안 쓰는 것이 잘못이 아니다")
    void V5_WBS_미설정은_제외() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(null, null, null, 60, 1))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V5_OUT_OF_WBS));
    }

    @Test
    @DisplayName("V5: 시작·끝 중 하나만 있으면 구간이 성립하지 않아 제외")
    void V5_한쪽만_설정되면_제외() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(null, MONDAY.plusDays(3), null, 60, 1))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V5_OUT_OF_WBS));
    }

    // ═══════════════════ V6 마감일 이후 (계약 §3.3 V6) ═══════════════════

    @Test
    @DisplayName("V6: 마감 당일 배치는 무위반 — 판정식이 > 이므로 같으면 통과")
    void V6_마감_당일은_무위반() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(MONDAY, null, null, 60, 1))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V6_AFTER_DUE_DATE));
    }

    @Test
    @DisplayName("V6: 마감 다음날 배치는 위반 + 문구 정본")
    void V6_마감_이후면_위반() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(MONDAY.minusDays(1), null, null, 60, 1))));

        ValidationIssue v6 = r.issues().stream()
                .filter(i -> i.ruleId() == RuleId.V6_AFTER_DUE_DATE).findFirst().orElseThrow();
        assertEquals("월요일 배치가 마감일 이후입니다. (마감 2026-07-26, 배치 2026-07-27)", v6.reason());
    }

    @Test
    @DisplayName("V6: dueDate 가 null 이면 판정 제외")
    void V6_마감_없으면_제외() {
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 10, 60)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(null, null, null, 60, 1))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V6_AFTER_DUE_DATE));
    }

    @Test
    @DisplayName("V6: 자정 넘는 블록은 시작 날짜로 판단 — 마감일 23시 시작은 위반이 아니다")
    void V6_자정_교차는_시작_날짜로_판단() {
        // 끝 날짜로 보면 마감 당일에 일하는 정상 배치가 경고를 받는다.
        ValidationReport r = engine.validate(snapshotWithFacts(
                List.of(taskBlockOf(BLOCK_A, TASK_1, 0, 23, 120)),
                List.of(allDayMonday()),
                Map.of(TASK_1, new TaskFacts(MONDAY, null, null, 120, 1))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V6_AFTER_DUE_DATE));
    }

    // ═══════════════════ V7 버퍼 부족 (us-decisions-kr §2 Q2 · D-19) ═══════════════════

    @Test
    @DisplayName("V7: 경계 — 버퍼가 정확히 10%면 미발생 (판정식이 < 이므로 같으면 통과)")
    void V7_경계_정확히_10퍼센트면_미발생() {
        // 가용 600분(09~19), 배치 540분 → 버퍼 60분. 60×100 == 600×10 이라 미발생.
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 540)), List.of(mondayAvail(9, 19))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V7_BUFFER_SHORTAGE));
    }

    @Test
    @DisplayName("V7: 버퍼가 10% 미만이면 발생 + 문구 정본")
    void V7_10퍼센트_미만이면_발생() {
        // 가용 600분, 배치 541분 → 버퍼 59분. 5900 < 6000.
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 541)), List.of(mondayAvail(9, 19))));

        ValidationIssue v7 = r.issues().stream()
                .filter(i -> i.ruleId() == RuleId.V7_BUFFER_SHORTAGE).findFirst().orElseThrow();
        assertEquals(Severity.WARNING, v7.severity());
        assertEquals(DayOfWeek.MONDAY, v7.weekday());
        assertEquals("월요일 여유 시간이 부족합니다. (가용 600분 / 배치 541분, 남은 59분)", v7.reason());
    }

    @Test
    @DisplayName("V7: V3와 상호 배타 — 초과한 요일에는 버퍼 부족이 붙지 않는다")
    void V7_V3와_상호_배타() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 90)), List.of(mondayAvail(9, 10))));

        assertTrue(r.issues().stream().anyMatch(i -> i.ruleId() == RuleId.V3_CAPACITY_EXCEEDED));
        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V7_BUFFER_SHORTAGE),
                "같은 요일에 초과와 여유 부족이 함께 나오면 사용자가 무엇을 해야 하는지 알 수 없다");
    }

    @Test
    @DisplayName("V7: 가용 0분인 요일은 미발생 — 계약의 'V7 미발생(배치가 있으면 V4 영역)'")
    void V7_가용_0인_요일은_미발생() {
        ValidationReport r = engine.validate(
                snapshot(List.of(blockOnMonday(9, 60)), List.of(inactiveAvail(DayOfWeek.MONDAY, 9, 12))));

        assertTrue(r.issues().stream().noneMatch(i -> i.ruleId() == RuleId.V7_BUFFER_SHORTAGE));
        assertTrue(r.issues().stream().anyMatch(i -> i.ruleId() == RuleId.V4_OUT_OF_AVAILABILITY));
    }

    @Test
    @DisplayName("V7: 배치가 없는 요일은 미발생 — 여유가 100%다")
    void V7_배치_없으면_미발생() {
        ValidationReport r = engine.validate(snapshot(List.of(), List.of(mondayAvail(9, 19))));

        assertTrue(r.issues().isEmpty());
    }
}
