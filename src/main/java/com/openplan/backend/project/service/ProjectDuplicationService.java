package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.dto.DuplicateProjectRequest;
import com.openplan.backend.project.dto.DuplicationPreviewResponse;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.domain.WbsItem;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.repository.WbsItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 프로젝트 복제 유스케이스 (PROJ-10·11·12). 복제 프리뷰(개요 확인)와 복제 실행을 한 관심사로 묶는다.
 *
 * <p>실행은 프리뷰가 정한 "무엇을 복제하는가"(태스크·WBS, 주간 계획 항목 제외)를 그대로 따른다 —
 * 두 응답이 어긋나면 사용자는 확인 화면과 다른 결과를 받는다. 마감일 처리도 같은 이유로 프리뷰
 * {@code note}에 미리 알린다({@link #NOTE_DUE_DATE_DROPPED}).
 */
@Service
public class ProjectDuplicationService {

    /** 프리뷰 안내(P4 — 사실 서술). 복제본 태스크가 미배치로 생성됨을 미리 알린다(정본 note). */
    private static final String NOTE = "주간 계획에 배치된 항목은 복제되지 않습니다 — 복제본의 태스크는 전량 미배치로 생성됩니다.";

    /**
     * 마감일이 이미 지난 프로젝트에만 덧붙는 안내. 실행이 조용히 마감일을 비우는데(§resolveDueDate)
     * 확인 화면이 그 말을 안 하면, 사용자는 프리뷰를 보고 승인했는데 결과가 달라진다 —
     * 같은 동작을 두 엔드포인트가 다르게 말하지 않도록 여기서 미리 알린다.
     * 해당하지 않는 프로젝트에는 붙이지 않는다(사실이 아닌 경고를 띄우지 않는다).
     */
    private static final String NOTE_DUE_DATE_DROPPED =
            " 마감일이 이미 지나 복제본은 마감일 없이(무기한) 생성됩니다.";

    /** 기본 복제본 이름 접미사 — projects.name VARCHAR(100) 안에 들어가도록 원본명을 자를 때 기준. */
    private static final String COPY_SUFFIX = " (복제)";
    private static final int NAME_MAX = 100;

    /** 로그에 남길 클라이언트 헤더 최대 길이(정본상 uuid=36자 — 여유를 두고 자른다). */
    private static final int LOG_HEADER_MAX = 64;

    private static final Logger log = LoggerFactory.getLogger(ProjectDuplicationService.class);

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WbsItemRepository wbsItemRepository;
    private final ProjectValidator validator;
    private final UserClock clock;

    public ProjectDuplicationService(ProjectRepository projectRepository, TaskRepository taskRepository,
                                     WbsItemRepository wbsItemRepository, ProjectValidator validator,
                                     UserClock clock) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.wbsItemRepository = wbsItemRepository;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * 복제 프리뷰 (getDuplicationPreview). 복제 시 딸려 올 항목 개요(이름·설명·태스크 수·WBS 수)를 반환한다.
     * 프리뷰는 조회라 프로젝트 상태(CLOSED/PAUSED) 무관하게 허용한다. 부재·타인 → 404. 읽기 tx.
     */
    @Transactional(readOnly = true)
    public DuplicationPreviewResponse preview(UUID userId, UUID projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인)

        long taskCount = taskRepository.countByProjectId(projectId);
        long wbsItemCount = wbsItemRepository.countByProjectId(projectId);

        boolean dueDateWillDrop =
                resolveDueDate(project.getDueDate(), clock.todayOf(userId)) == null
                        && project.getDueDate() != null;
        String note = dueDateWillDrop ? NOTE + NOTE_DUE_DATE_DROPPED : NOTE;

        return new DuplicationPreviewResponse(
                project.getName(), project.getDescription(), taskCount, wbsItemCount, note);
    }

    /**
     * 복제 실행 (duplicateProject). 프로젝트+태스크+WBS를 새 id로 통째 복사한다(한 tx, 원자적).
     * 복제본 프로젝트는 IN_PROGRESS, 태스크는 전량 UNASSIGNED. 주간계획 블록·수행이력은 복사하지 않는다.
     * 원본은 무변경. 부재·타인 → 404.
     *
     * <p><b>Idempotency-Key</b>: 복제는 자연 멱등이 아니라 더블클릭 시 복제본이 둘 생길 수 있다(확정과 다름 —
     * 확정은 상태 멱등). 서버 강제 dedup(키 저장소)은 전 도메인 공통 인프라라 이 슬라이스 범위 밖이며,
     * 키는 관측용 로그로만 남긴다(확정과 동일 관례). 이중 생성 방지는 후속 과제.
     */
    @Transactional
    public ProjectResponse duplicate(UUID userId, UUID projectId, DuplicateProjectRequest request,
                                     String idempotencyKey) {
        Project source = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인)

        Instant now = clock.now();
        String newName = resolveNewName(request, source.getName());
        LocalDate dueDate = resolveDueDate(source.getDueDate(), clock.todayOf(userId));

        // 1) 프로젝트 복제 — 새 id, IN_PROGRESS, description·우선순위 승계(마감일은 아래 규칙).
        Project copy = new Project(userId, newName, source.getDescription(),
                dueDate, source.getPriority(), now);
        projectRepository.save(copy);

        // 2) 태스크 복제 — 전량 UNASSIGNED. oldTaskId → newTaskId 매핑(WBS 재연결용).
        Map<UUID, UUID> taskIdMap = new HashMap<>();
        for (Task src : taskRepository.findByProjectIdOrderByIdAsc(projectId)) {
            Task dup = new Task(copy.getId(), src.getCategoryId(), src.getTitle(), src.getMemo(),
                    src.getEstimatedMinutes(), src.getPriority(), src.getDueDate(), now);
            taskRepository.save(dup);
            taskIdMap.put(src.getId(), dup.getId());
        }

        // 3) WBS 복제 — 새 project_id + 매핑된 새 task_id로 재연결.
        for (WbsItem src : wbsItemRepository.findByProjectId(projectId)) {
            UUID newTaskId = taskIdMap.get(src.getTaskId());
            if (newTaskId == null) {
                continue; // 이론상 불가(WBS는 태스크에 종속) — 방어
            }
            wbsItemRepository.save(WbsItem.create(copy.getId(), newTaskId, src.getStartDate(), src.getEndDate(), now));
        }

        log.info("project duplicated: sourceId={}, newId={}, userId={}, idempotencyKey={}",
                projectId, copy.getId(), userId, sanitizeForLog(idempotencyKey));
        return ProjectResponse.from(copy);
    }

    /**
     * 로그로 나갈 클라이언트 헤더 정리. {@code Idempotency-Key}는 <b>외부 입력</b>이라 그대로 찍으면
     * 신뢰 경계를 넘는다.
     *
     * <ul>
     *   <li><b>CRLF 제거</b> — 기본 로그 패턴은 개행을 이스케이프하지 않으므로 값에 줄바꿈을 넣으면
     *       위조된 로그 줄을 통째로 삽입할 수 있다(다른 사용자의 가짜 ERROR를 심는 등).</li>
     *   <li><b>길이 절단</b> — 정본이 이 헤더를 uuid로 규정하지만 서버가 형식을 강제하지 않으므로,
     *       거대한 값으로 로그를 부풀리는 것을 막는다.</li>
     * </ul>
     */
    private static String sanitizeForLog(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String oneLine = headerValue.replaceAll("[\\r\\n]", "_");
        return oneLine.length() <= LOG_HEADER_MAX
                ? oneLine
                : oneLine.substring(0, LOG_HEADER_MAX) + "…(truncated)";
    }

    /**
     * 복제본 마감일 — 원본이 <b>이미 지난</b> 마감일이면 승계하지 않고 null(무기한)로 떨어뜨린다.
     *
     * <p>복제본은 항상 IN_PROGRESS로 태어나는데, 지난 마감일을 그대로 물려주면
     * {@code IN_PROGRESS ∧ dueDate < today} — {@link ProjectAutoCloseEvaluator#closeOverdue}가
     * 일괄 종료시키는 바로 그 형태가 된다. 목록·상세·수정·상태변경 어느 진입점이든 다음 호출에서
     * 평가가 돌아 복제본이 CLOSED로 뒤집히므로, <b>201이 반환한 IN_PROGRESS가 거짓이 된다</b>.
     *
     * <p>생성(422 {@code dueDate.past})·재개(E-PROJ-004)가 둘 다 거부하는 상태라 복제만 만들어낼
     * 이유가 없다. 그렇다고 422로 막으면 "지난 분기 프로젝트 재활용"이라는 복제의 주 용도가 통째로
     * 불가능해진다 — 원본 마감일은 사용자가 이 요청에서 손댈 수 없는 값이다(이름 길이와 같은 판단).
     * null은 생성이 이미 허용하는 유효 상태이므로 복제는 성사시키고 마감일만 비운다.
     *
     * <p>경계는 {@code isBefore(today)} — 오늘 마감은 승계한다({@code bulkCloseOverdue}의
     * {@code dueDate < today}, {@code ProjectValidator.validateDueDate}와 같은 경계).
     */
    private static LocalDate resolveDueDate(LocalDate sourceDueDate, LocalDate today) {
        return (sourceDueDate != null && sourceDueDate.isBefore(today)) ? null : sourceDueDate;
    }

    /**
     * newName 미지정(null·공백) → "원본명 (복제)". 지정 시 생성과 동일 규칙으로 검증(422).
     *
     * <p>기본명은 접미사를 붙이므로 원본 이름이 이미 길면 {@code projects.name VARCHAR(100)}을 넘긴다
     * (95자 원본 → 101자). 원본 이름은 사용자가 이 요청에서 손댈 수 없는 값이라 422로 막으면 복제 자체가
     * 불가능해지므로, 접미사가 들어갈 만큼 원본명을 잘라 붙인다.
     */
    private String resolveNewName(DuplicateProjectRequest request, String sourceName) {
        if (request == null || request.newName() == null || request.newName().isBlank()) {
            return truncateForSuffix(sourceName) + COPY_SUFFIX;
        }
        return validator.validateName(request.newName());
    }

    /**
     * 접미사가 들어갈 자리를 남기고 원본명을 자른다. 짧으면 그대로 반환한다.
     *
     * <p><b>서로게이트 쌍을 쪼개지 않는다.</b> 이모지·보조평면 문자는 Java에서 {@code char} 두 칸을
     * 차지하는데, 자르는 지점이 그 사이에 떨어지면 짝 잃은 상위 서로게이트가 남는다. 그 문자열은
     * 더 이상 유효한 유니코드가 아니라서 pgjdbc가 {@code ?}로 바꿔 넣는다 — 예외도 없이 이름만
     * 조용히 망가진다. 마지막 칸이 상위 서로게이트면 한 칸 더 당겨 쌍을 통째로 버린다.
     *
     * <p>길이 판정을 {@code String.length()}(UTF-16 코드유닛)로 하는 것은 안전한 쪽으로 보수적이다 —
     * Postgres {@code VARCHAR(100)}은 코드포인트를 세므로 코드유닛 수가 항상 그 이상이다. 이모지가
     * 있으면 필요보다 조금 일찍 자를 뿐 넘치지는 않는다.
     */
    private static String truncateForSuffix(String sourceName) {
        int cut = NAME_MAX - COPY_SUFFIX.length();
        if (sourceName.length() <= cut) {
            return sourceName;
        }
        if (Character.isHighSurrogate(sourceName.charAt(cut - 1))) {
            cut--; // 짝이 잘릴 자리 — 쌍 전체를 버린다
        }
        return sourceName.substring(0, cut);
    }
}
