package com.openplan.backend.task.repository;

import com.openplan.backend.task.domain.WbsItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS 기간 저장소 (ST-B2-05). {@code wbs_items}는 task와 1:0..1(UNIQUE task_id) — 소유 판정은
 * 이 저장소가 하지 않는다(호출 전에 {@code TaskRepository.findOwnedWithProjectStatus}로 이미 끝남).
 */
public interface WbsItemRepository extends JpaRepository<WbsItem, UUID> {

    Optional<WbsItem> findByTaskId(UUID taskId);

    /**
     * 여러 태스크의 WBS 기간을 한 번에 (계획 검증 V5 — PLAN-27). 블록이 가리키는 태스크가 여러 개라
     * {@link #findByTaskId}를 루프로 돌리면 N+1이 된다. WBS 미설정 태스크는 행이 없어 결과에서 빠지고,
     * 호출 측이 그것을 "판정 제외"로 읽는다(엔진 계약 §3.3 V5 "WBS 미설정은 판정 제외").
     *
     * <p>소유 판정은 여기서 하지 않는다(이 인터페이스 javadoc 승계) — 호출 측이 이미 사용자 스코프로
     * 좁힌 taskId만 넘긴다.
     */
    List<WbsItem> findByTaskIdIn(Collection<UUID> taskIds);

    /** 프로젝트의 WBS 항목 수 (복제 프리뷰 — PROJ-11). */
    long countByProjectId(UUID projectId);

    /** 프로젝트의 WBS 전량 (복제 실행 — PROJ-12). 복제본 태스크에 재연결하기 위해 task_id별로 읽는다. */
    List<WbsItem> findByProjectId(UUID projectId);

    /**
     * WBS 뷰 (PROJ-13 / GET) — 기간 바 렌더링용으로 태스크 제목을 조인해 함께 읽는다(N+1 회피).
     * 정렬은 서버 고정(D-9): 시작일 오름차순 = 타임라인 위에서 아래로. 같은 시작일은 짧은 바가 먼저,
     * 그마저 같으면 wbs_item_id로 완전 결정적이게 한다(페이지네이션 없는 전량 반환이라 tie도 안정 필요).
     */
    @Query("""
            select new com.openplan.backend.task.repository.WbsItemRow(w, t.title)
              from WbsItem w, Task t
             where t.id = w.taskId
               and w.projectId = :projectId
             order by w.startDate asc, w.endDate asc, w.id asc
            """)
    List<WbsItemRow> findViewsByProjectId(@Param("projectId") UUID projectId);

    /**
     * WBS 기간 업서트 (PUT — 있으면 갱신, 없으면 생성). {@code INSERT ... ON CONFLICT (task_id)
     * DO UPDATE} 단일 원자문으로 동시 요청의 UNIQUE 경합을 흡수한다.
     *
     * <p><b>왜 "엔티티 save 후 DataIntegrityViolationException catch" 방식을 쓰지 않는가</b>: 그
     * 패턴은 {@code saveAndFlush} 실패 시점에 트랜잭션이 rollback-only로 마킹되어, catch 블록이
     * 정상 흐름으로 복귀해도 커밋 시 {@code UnexpectedRollbackException}(500)이 터진다(같은 주
     * 다른 슬라이스에서 동시 요청 30건 중 15건 500으로 실측된 함정). 원자적 업서트는 예외 분기 자체를
     * 없애 그 창을 원천 차단한다.
     *
     * <p><b>왜 {@code DO NOTHING}이 아니라 {@code DO UPDATE}인가</b>: 이 엔드포인트는 PUT "설정"
     * 의미다(get-or-create 성격의 주간계획 get-or-create와 다름) — 늦게 도착한 요청의 값이 조용히
     * 버려지면 호출자는 200을 받고도 자신이 보낸 날짜가 반영되지 않는 사일런트 실패를 겪는다.
     * last-write-wins(DO UPDATE)가 "마지막 PUT이 최종값"이라는 PUT의 멱등 계약과 맞다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO wbs_items (wbs_item_id, project_id, task_id, start_date, end_date, created_at)
            VALUES (gen_random_uuid(), :projectId, :taskId, :startDate, :endDate, now())
            ON CONFLICT (task_id) DO UPDATE
               SET start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date
            """, nativeQuery = true)
    void upsert(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId,
                @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
