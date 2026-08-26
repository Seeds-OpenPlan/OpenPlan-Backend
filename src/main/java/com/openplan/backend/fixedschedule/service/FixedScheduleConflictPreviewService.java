package com.openplan.backend.fixedschedule.service;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.fixedschedule.dto.ConflictPreviewRequest;
import com.openplan.backend.fixedschedule.dto.WeekConflictResponse;
import com.openplan.backend.fixedschedule.repository.FixedScheduleRepository;
import com.openplan.backend.fixedschedule.repository.FixedScheduleWeekExceptionRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.FixedWindow;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.RuleId;
import com.openplan.backend.rule.model.ValidationIssue;
import com.openplan.backend.rule.port.PlanValidationPort;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.ValidationIssueResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import com.openplan.backend.weeklyplan.service.PlanSnapshotAssembler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 고정 일정 충돌 미리보기 (FIX-07·08 / {@code POST /fixed-schedules/conflict-previews}).
 *
 * <p>저장 전 후보를 "있는 셈 치고" <b>저장된 주간 계획 전량</b>에 V2를 돌린다. 정본이 대상을
 * "저장된 주간 계획"이라고만 하고 범위를 좁히지 않아 과거 주도 뺴지 않는다 — 후보 유효기간
 * ({@code startDate}/{@code endDate}) 밖 주는 V2가 스스로 0건으로 처리하므로 별도 필터도 두지 않는다.
 *
 * <p><b>규칙은 만들지 않는다</b> — {@link PlanValidationPort}(엔진)에 넘기고 결과를 추려 낸다.
 * 엔진은 V1~V7을 통째로 돌지만 <b>후보가 일으킨 V2만</b> 반환한다: 기존 고정 일정끼리의 충돌이나
 * 블록 겹침은 이 후보를 저장하든 말든 이미 있던 문제라 "추가하면 생기는 충돌"이 아니다.
 * 아무것도 저장하지 않는다(무영속 — {@code validationIssueId}는 항상 null).
 */
@Service
public class FixedScheduleConflictPreviewService {

    /** 한 주가 덮는 날 수 - 1. 주는 [weekStartDate, weekStartDate+6]이라 하한을 6일 앞당겨야 안 샌다. */
    private static final int WEEK_SPAN_DAYS = 6;

    private final FixedScheduleRepository fixedScheduleRepository;
    private final FixedScheduleWeekExceptionRepository weekExceptionRepository;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final PlanBlockRepository planBlockRepository;
    private final PlanSnapshotAssembler assembler;
    private final PlanValidationPort validationPort;
    private final FixedScheduleValidator validator;

    public FixedScheduleConflictPreviewService(FixedScheduleRepository fixedScheduleRepository,
                                               FixedScheduleWeekExceptionRepository weekExceptionRepository,
                                               WeeklyPlanRepository weeklyPlanRepository,
                                               PlanBlockRepository planBlockRepository,
                                               PlanSnapshotAssembler assembler,
                                               PlanValidationPort validationPort,
                                               FixedScheduleValidator validator) {
        this.fixedScheduleRepository = fixedScheduleRepository;
        this.weekExceptionRepository = weekExceptionRepository;
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.assembler = assembler;
        this.validationPort = validationPort;
        this.validator = validator;
    }

    /**
     * 충돌 미리보기. 검사 순서는 <b>404 → 422</b>(도메인 관례 D-10): 편집인데 그 고정 일정이 없거나
     * 타인 것이면 값 검증보다 먼저 404로 끊는다(존재 은닉 — 남의 id 존재 여부를 값 오류로 떠보지 못하게).
     *
     * @return 충돌이 <b>있는 주만</b> 주차 오름차순으로. 충돌 0건이면 빈 배열(오류 아님).
     */
    @Transactional(readOnly = true)
    public List<WeekConflictResponse> preview(UUID userId, ConflictPreviewRequest request) {
        ConflictPreviewRequest.Candidate candidate = candidateOf(request);

        UUID editingId = candidate.fixedScheduleId();
        if (editingId != null) { // 편집 — 소유 확인 먼저 (404)
            fixedScheduleRepository.findByIdAndUserId(editingId, userId)
                    .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        }

        validator.validateTitle(candidate.title()); // 제목은 판정에 안 쓰지만 생성과 같은 규칙으로 막는다
        Weekday weekday = validator.resolveWeekday(candidate.weekday());
        validator.validateTimes(candidate.startTime(), candidate.endTime());
        validator.validateDates(candidate.startDate(), candidate.endDate());

        // 생성이면 아직 id가 없다 — 후보가 일으킨 이슈만 골라내기 위한 임시 식별자를 붙인다(무영속).
        UUID candidateId = editingId != null ? editingId : UUID.randomUUID();
        FixedWindow candidateWindow = new FixedWindow(candidateId, toDayOfWeek(weekday),
                candidate.startTime(), candidate.endTime(), candidate.startDate(), candidate.endDate());

        // 편집 대상에 걸린 주차 예외(PLAN-33)는 저장 후에도 살아남는다 — 그 주엔 후보를 넣지 않는다.
        // 넣으면 "저장해도 실제론 안 생길 충돌"을 경고하게 된다.
        Set<LocalDate> exceptedWeeks = editingId == null
                ? Set.of()
                : new HashSet<>(weekExceptionRepository.findWeekStartDatesByFixedScheduleId(editingId));

        // 이슈가 나올 수 없는 주는 쿼리 단계에서 뺀다(정책 아님 — 근거는 findForConflictPreview 참고).
        LocalDate fromBound = candidate.startDate() == null ? null : candidate.startDate().minusDays(WEEK_SPAN_DAYS);
        LocalDate toBound = candidate.endDate();

        List<WeekConflictResponse> result = new ArrayList<>();
        for (WeeklyPlan plan : weeklyPlanRepository.findForConflictPreview(userId, fromBound, toBound)) {
            if (exceptedWeeks.contains(plan.getWeekStartDate())) {
                continue;
            }
            List<ValidationIssueResponse> issues = candidateIssues(userId, plan, candidateWindow, editingId);
            if (!issues.isEmpty()) {
                result.add(new WeekConflictResponse(plan.getWeekStartDate(), issues));
            }
        }
        return result;
    }

    /** 한 주차 판정 — 후보를 유효 목록에 넣고(편집이면 기존 자신은 빼고) 돌린 뒤 후보발 V2만 추린다. */
    private List<ValidationIssueResponse> candidateIssues(UUID userId, WeeklyPlan plan,
                                                          FixedWindow candidateWindow, UUID editingId) {
        List<BlockView> blocks = planBlockRepository.findByWeeklyPlanId(plan.getId()).stream()
                .map(FixedScheduleConflictPreviewService::toBlockView)
                .toList();

        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blocks, Map.of(),
                List.of(candidateWindow), editingId);

        return validationPort.validate(snapshot).issues().stream()
                .filter(i -> i.ruleId() == RuleId.V2_FIXED_CONFLICT)
                .filter(i -> candidateWindow.fixedScheduleId().equals(i.counterpartId()))
                .map(i -> toResponse(i, editingId))
                .toList();
    }

    /**
     * 엔진 판정 → 응답. 생성 미리보기는 {@code counterpartId}를 null로 지운다 — 임시 식별자는 서버 내부
     * 표식일 뿐 가리킬 고정 일정 행이 아직 없어서, 그대로 내보내면 존재하지 않는 id를 준 셈이 된다.
     */
    private static ValidationIssueResponse toResponse(ValidationIssue issue, UUID editingId) {
        ValidationIssueResponse r = ValidationIssueResponse.of(issue, null); // 무영속 → id null
        return editingId != null ? r : new ValidationIssueResponse(
                r.validationIssueId(), r.ruleId(), r.severity(), r.planBlockId(), null,
                r.taskId(), r.weekday(), r.reason(), r.resolutionStatus());
    }

    /** {@code WeeklyPlanValidationService#toBlockView}와 동일 매핑 — 엔진 입력 형태가 하나뿐이라 형태를 맞춘다. */
    private static BlockView toBlockView(PlanBlock b) {
        return new BlockView(b.getId(), BlockType.valueOf(b.getBlockType().name()),
                b.getTaskId(), b.getScheduleId(), b.getStartAt(), b.getEndAt());
    }

    private static ConflictPreviewRequest.Candidate candidateOf(ConflictPreviewRequest request) {
        if (request == null || request.candidate() == null) {
            throw new OpenPlanException(ErrorCode.E_COM_009,
                    Map.of("fields", List.of(Map.of("field", "candidate", "rule", "required"))));
        }
        return request.candidate();
    }

    /** common.Weekday(MON..SUN) → java DayOfWeek(MONDAY..SUNDAY). 선언 순서가 월~일로 1:1. */
    private static DayOfWeek toDayOfWeek(Weekday weekday) {
        return DayOfWeek.of(weekday.ordinal() + 1);
    }
}
