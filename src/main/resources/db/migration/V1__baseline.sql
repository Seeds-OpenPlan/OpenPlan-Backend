-- =====================================================================================
-- OpenPlan MVP1 — V1 baseline (28 테이블)
--
-- 생성 소유: BE-1 (code-structure.md §3 · ADR-0002 §1) — **1회 생성 후 동결**.
-- 이후 스키마 변경은 레인별 신규 파일 `V{yyyyMMddHHmm}__{lane}_{desc}.sql` (build-plan §4).
--
-- 정본: .agent-team/04-architecture/data-model-erd.md §1~2 (B1~B13 확정 변경)
--       + OpenPlan문서/12. 상세 ERD (Mermaid).md (논리모델 07-15 승계 컬럼)
-- 공통 규약 (data-model-erd §2 머리말):
--   · PK = UUID DEFAULT gen_random_uuid()  (PG13+ 내장, pgcrypto 불요)
--   · created_at TIMESTAMPTZ NOT NULL DEFAULT now() — 전 테이블
--   · 시각 컬럼은 전부 TIMESTAMPTZ (저장은 UTC, 사용자 timezone은 user_profiles 보유)
--   · 열거값 문자열은 data-model-erd §5 열거형 사전과 1:1 (DB=API 동일 문자열, P3)
--   · 5분 단위(NFR-010)는 CHECK로 구조 강제 — 서버 검증과 이중 방어
--
-- D-16 불변식 (§8): 전 도메인 테이블은 소유 경로로 users에 닿는다.
--   예외는 사용자 무소속 공개 콘텐츠 2개(announcements, help_articles)뿐.
--   authN은 4주차지만 이 소유 구조는 baseline 고정 — 나중에 끼우면 전 테이블·전 쿼리 재작업.
-- =====================================================================================

-- B11 · ADR-0009: 확장 활성화만. MVP1 벡터 컬럼 0 (MVP2 경로 확보).
CREATE EXTENSION IF NOT EXISTS vector;


-- =====================================================================================
-- 1. 사용자 · 인증  (BE-1)
-- =====================================================================================

CREATE TABLE users (
    user_id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                    VARCHAR(255) NOT NULL UNIQUE,
    password_hash            VARCHAR(255),                       -- 소셜 전용 계정은 NULL
    login_type               VARCHAR(10)  NOT NULL,
    social_provider          VARCHAR(10),
    social_provider_user_id  VARCHAR(255),
    is_email_verified        BOOLEAN      NOT NULL DEFAULT false,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    deactivation_requested_at TIMESTAMPTZ,                       -- ACCT-04
    scheduled_deletion_at    TIMESTAMPTZ,                        -- ACCT-04/06 (30일 복구창)
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_login_type      CHECK (login_type IN ('LOCAL', 'SOCIAL')),
    CONSTRAINT ck_users_social_provider CHECK (social_provider IS NULL OR social_provider IN ('GOOGLE', 'NAVER', 'KAKAO')),
    CONSTRAINT ck_users_status          CHECK (status IN ('ACTIVE', 'LOCKED', 'DEACTIVATED')),
    -- LOCAL은 비밀번호 필수 / SOCIAL은 제공자·제공자측 ID 필수 (AUTH-01/02 전제)
    CONSTRAINT ck_users_credential      CHECK (
        (login_type = 'LOCAL'  AND password_hash IS NOT NULL)
     OR (login_type = 'SOCIAL' AND social_provider IS NOT NULL AND social_provider_user_id IS NOT NULL)
    )
);
-- A9: 계정 삭제 배치 — 기한 경과 DEACTIVATED 스캔 (ACCT-06)
CREATE INDEX ix_users_deletion_scan ON users (status, scheduled_deletion_at);
-- 소셜 콜백의 users upsert 키 (SQ-12). ERD 표에는 없으나 upsert가 성립하려면 필요 —
-- 없으면 콜백 재시도마다 계정이 중복 생성된다.
CREATE UNIQUE INDEX ux_users_social_identity
    ON users (social_provider, social_provider_user_id)
    WHERE social_provider IS NOT NULL;

CREATE TABLE user_profiles (
    profile_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL UNIQUE REFERENCES users (user_id) ON DELETE CASCADE,
    name            VARCHAR(50) NOT NULL,
    purpose         VARCHAR(100),                                -- 사용 목적 (ONB-02)
    timezone        VARCHAR(50) NOT NULL DEFAULT 'Asia/Seoul',
    week_start_day  VARCHAR(3)  NOT NULL DEFAULT 'MON',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_profiles_week_start CHECK (week_start_day IN ('MON','TUE','WED','THU','FRI','SAT','SUN'))
);

-- B3 · FIX-10~12: 기본값 저장처. 프로필(정체성)과 동작 기본값(설정)의 분리 (ERD §7 분기 2).
CREATE TABLE user_preferences (
    user_id                    UUID        PRIMARY KEY REFERENCES users (user_id) ON DELETE CASCADE,
    default_estimated_minutes  INTEGER,                          -- FIX-11 (5분 단위)
    default_replan_strategy    VARCHAR(30),                      -- FIX-12
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_pref_estimated CHECK (default_estimated_minutes IS NULL
                                             OR (default_estimated_minutes % 5 = 0 AND default_estimated_minutes > 0)),
    CONSTRAINT ck_user_pref_replan    CHECK (default_replan_strategy IS NULL
                                             OR default_replan_strategy IN ('KEEP_CURRENT','MINIMAL_CHANGE','DEADLINE_FIRST','WORKLOAD_BALANCE'))
);

-- tutorial_sample_project_id FK는 projects 생성 후 §9에서 ALTER로 추가 (순환 순서 회피).
CREATE TABLE onboarding_progress (
    user_id                    UUID        PRIMARY KEY REFERENCES users (user_id) ON DELETE CASCADE,
    intro_done                 BOOLEAN     NOT NULL DEFAULT false,
    profile_done               BOOLEAN     NOT NULL DEFAULT false,
    availability_done          BOOLEAN     NOT NULL DEFAULT false,
    fixed_schedule_done        BOOLEAN     NOT NULL DEFAULT false,
    tutorial_done              BOOLEAN     NOT NULL DEFAULT false,
    calendar_sync_done         BOOLEAN     NOT NULL DEFAULT false,
    tutorial_sample_project_id UUID,                             -- B13 (GAP-1) — non-null = 샘플 잔존
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth_sessions (
    session_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    refresh_token_hash  VARCHAR(255) NOT NULL UNIQUE,             -- ADR-0001 refresh 회전·폐기
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    previous_path       VARCHAR(255),                             -- AUTH-08 만료 후 문맥 복귀
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 논리모델 '유효/만료/종료'의 영문 확정. data-model-erd §5 열거 사전에는 없는 레인 로컬 값.
    CONSTRAINT ck_auth_sessions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED'))
);
-- A13: 만료 세션 정리 스캔
CREATE INDEX ix_auth_sessions_expires ON auth_sessions (expires_at);

CREATE TABLE auth_tokens (
    token_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    token_type  VARCHAR(30) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,                                      -- NFR-005 1회용 보장
    status      VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_auth_tokens_type   CHECK (token_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EXTERNAL_AUTH')),
    CONSTRAINT ck_auth_tokens_status CHECK (status IN ('ISSUED', 'USED', 'EXPIRED'))
);
CREATE INDEX ix_auth_tokens_user_type ON auth_tokens (user_id, token_type);


-- =====================================================================================
-- 2. 가용 시간 · 외부 연동  (BE-1)
-- =====================================================================================

CREATE TABLE availability_patterns (
    availability_pattern_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    weekday                 VARCHAR(3)  NOT NULL,
    start_time              TIME        NOT NULL,
    end_time                TIME        NOT NULL,
    is_active               BOOLEAN     NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A4: 요일당 1행 (PUT 전체 교체 7행 — ST-B1-09)
    CONSTRAINT ux_availability_user_weekday UNIQUE (user_id, weekday),
    CONSTRAINT ck_availability_weekday CHECK (weekday IN ('MON','TUE','WED','THU','FRI','SAT','SUN')),
    CONSTRAINT ck_availability_range   CHECK (start_time < end_time),
    -- NFR-010 5분 단위 경계 (E-COM-009와 이중 방어)
    CONSTRAINT ck_availability_step    CHECK (
        EXTRACT(EPOCH FROM start_time)::BIGINT % 300 = 0
    AND EXTRACT(EPOCH FROM end_time)::BIGINT   % 300 = 0
    )
);

CREATE TABLE external_calendar_connections (
    connection_id      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    provider           VARCHAR(20)  NOT NULL,
    account_identifier VARCHAR(255) NOT NULL,
    sync_mode          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    connected_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- FIX-14 중복 연결 409 E-EXT-004의 구조적 근거 (ST-B1-11 AC1)
    CONSTRAINT ux_ext_conn_account UNIQUE (user_id, provider, account_identifier),
    CONSTRAINT ck_ext_conn_provider CHECK (provider IN ('GOOGLE', 'NAVER', 'KAKAO')),
    CONSTRAINT ck_ext_conn_status   CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- B5 · ONB-08/FIX-15: 가져올 캘린더 선택 상태의 저장처 (논리모델 부재분).
CREATE TABLE external_calendar_selections (
    selection_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id        UUID         NOT NULL REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    external_calendar_id VARCHAR(255) NOT NULL,
    calendar_name        VARCHAR(255) NOT NULL,
    is_active            BOOLEAN      NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_ext_selection UNIQUE (connection_id, external_calendar_id)
);

CREATE TABLE external_calendar_events (
    external_calendar_event_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    external_event_id          VARCHAR(255) NOT NULL,             -- 제공자 측 원본 이벤트 ID
    connection_id              UUID         NOT NULL REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    title                      VARCHAR(255) NOT NULL,
    start_at                   TIMESTAMPTZ  NOT NULL,
    end_at                     TIMESTAMPTZ  NOT NULL,
    source_calendar            VARCHAR(255),
    apply_mode                 VARCHAR(20),                       -- ONB-09 그대로/수정/제외
    apply_status               VARCHAR(20)  NOT NULL DEFAULT 'CANDIDATE',
    synced_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_ext_event UNIQUE (connection_id, external_event_id),
    CONSTRAINT ck_ext_event_range        CHECK (start_at < end_at),
    CONSTRAINT ck_ext_event_apply_mode   CHECK (apply_mode IS NULL OR apply_mode IN ('AS_IS', 'MODIFIED', 'EXCLUDED')),
    CONSTRAINT ck_ext_event_apply_status CHECK (apply_status IN ('CANDIDATE', 'APPLIED', 'EXCLUDED'))
);


-- =====================================================================================
-- 3. 고정 일정  (BE-2)
-- =====================================================================================

CREATE TABLE fixed_schedules (
    fixed_schedule_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    -- FIX-17 연동 해제 시 EXTERNAL 유래 고정 일정 함께 삭제 (ST-B1-11 AC4)
    connection_id     UUID         REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    title             VARCHAR(255) NOT NULL,
    weekday           VARCHAR(3)   NOT NULL,
    start_time        TIME         NOT NULL,
    end_time          TIME         NOT NULL,
    start_date        DATE,
    end_date          DATE,
    recurrence_rule   VARCHAR(255),
    source            VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
    -- ADR-0011 · B12: ACTIVE/INACTIVE는 **서버 관리 미러**(FIX-16 연동 비활성).
    -- PLAN-33/34의 주차 한정 제외는 이 컬럼이 아니라 fixed_schedule_week_exceptions.
    status            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    version           BIGINT       NOT NULL DEFAULT 0,            -- B6 낙관적 잠금
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_fixed_weekday CHECK (weekday IN ('MON','TUE','WED','THU','FRI','SAT','SUN')),
    CONSTRAINT ck_fixed_range   CHECK (start_time < end_time),
    CONSTRAINT ck_fixed_step    CHECK (
        EXTRACT(EPOCH FROM start_time)::BIGINT % 300 = 0
    AND EXTRACT(EPOCH FROM end_time)::BIGINT   % 300 = 0
    ),
    CONSTRAINT ck_fixed_dates   CHECK (start_date IS NULL OR end_date IS NULL OR start_date <= end_date),
    CONSTRAINT ck_fixed_source  CHECK (source IN ('MANUAL', 'EXTERNAL')),
    CONSTRAINT ck_fixed_status  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- MANUAL은 연결 없음 / EXTERNAL은 유래 연결 필수 (source 의미 보장)
    CONSTRAINT ck_fixed_origin  CHECK (
        (source = 'MANUAL'   AND connection_id IS NULL)
     OR (source = 'EXTERNAL' AND connection_id IS NOT NULL)
    )
);
-- A4: 검증 스냅샷 조립 시 해당 사용자의 유효 고정 일정
CREATE INDEX ix_fixed_schedules_user_status ON fixed_schedules (user_id, status);

-- B12 (리드 J6 재결정 07-17) · PLAN-33/34: 행 존재 = **그 주에서만** 배치 제약·V2 판정 제외.
CREATE TABLE fixed_schedule_week_exceptions (
    exception_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    fixed_schedule_id UUID        NOT NULL REFERENCES fixed_schedules (fixed_schedule_id) ON DELETE CASCADE,
    week_start_date   DATE        NOT NULL,                      -- 사용자 주 시작 요일 기준
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A4 주차 안티조인 + 중복 제외 방지
    CONSTRAINT ux_fixed_week_exception UNIQUE (fixed_schedule_id, week_start_date)
);


-- =====================================================================================
-- 4. 프로젝트 · 할 일  (BE-2)
-- =====================================================================================

CREATE TABLE projects (
    project_id  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    due_date    DATE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    priority    INTEGER,
    closed_at   TIMESTAMPTZ,                                     -- PROJ-08 자동 종료 시각 (ADR-0006 지연 평가 영속 시점)
    version     BIGINT       NOT NULL DEFAULT 0,                 -- B6
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_projects_status CHECK (status IN ('IN_PROGRESS', 'PAUSED', 'CLOSED'))
);
-- A3/A10: 프로젝트 목록 + 미배치 패널 + 자동 종료 지연 평가
CREATE INDEX ix_projects_user_status ON projects (user_id, status);

-- B1 · SC-01 · FR-303~305: 사용자별 카테고리 프리셋.
CREATE TABLE task_categories (
    task_category_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    name             VARCHAR(50) NOT NULL,
    sort_order       INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_task_category_name UNIQUE (user_id, name)
);

CREATE TABLE tasks (
    task_id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        UUID         NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    -- FR-305 "카테고리 삭제 시 '없음'으로 전환"을 FK 동작으로 보장 (B1)
    category_id       UUID         REFERENCES task_categories (task_category_id) ON DELETE SET NULL,
    title             VARCHAR(200) NOT NULL,
    memo              TEXT,
    estimated_minutes INTEGER,
    priority          INTEGER,
    due_date          DATE,
    status            VARCHAR(20)  NOT NULL DEFAULT 'UNASSIGNED',
    version           BIGINT       NOT NULL DEFAULT 0,           -- B6
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tasks_status    CHECK (status IN ('UNASSIGNED', 'IN_PROGRESS', 'COMPLETED')),
    -- FIX-11 · PLAN-15 5분 단위 일관 기준
    CONSTRAINT ck_tasks_estimated CHECK (estimated_minutes IS NULL
                                         OR (estimated_minutes % 5 = 0 AND estimated_minutes > 0))
);
CREATE INDEX ix_tasks_project_status ON tasks (project_id, status);     -- A3 미배치 패널
CREATE INDEX ix_tasks_project_due    ON tasks (project_id, due_date);   -- A5 마감 임박

CREATE TABLE wbs_items (
    wbs_item_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    task_id     UUID        NOT NULL UNIQUE REFERENCES tasks (task_id) ON DELETE CASCADE,  -- A4: 태스크와 1:0..1
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wbs_range CHECK (start_date <= end_date)       -- E-WBS-001
);

CREATE TABLE task_structuring_drafts (
    task_structuring_draft_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id                UUID         NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    title                     VARCHAR(200) NOT NULL,
    proposed_estimated_minutes INTEGER,
    proposed_priority         INTEGER,
    is_adopted                BOOLEAN      NOT NULL DEFAULT false,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_draft_estimated CHECK (proposed_estimated_minutes IS NULL
                                         OR (proposed_estimated_minutes % 5 = 0 AND proposed_estimated_minutes > 0))
);
CREATE INDEX ix_drafts_project ON task_structuring_drafts (project_id);


-- =====================================================================================
-- 5. 주간 계획  (BE-2)
-- =====================================================================================

CREATE TABLE weekly_plans (
    weekly_plan_id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    week_start_date       DATE        NOT NULL,                  -- base_week 삭제 — 이것이 유일 식별
    week_end_date         DATE        NOT NULL,
    total_planned_minutes INTEGER     NOT NULL DEFAULT 0,        -- 블록 합의 반정규화 캐시 (블록 변경 tx에서 갱신)
    status                VARCHAR(10) NOT NULL DEFAULT 'DRAFT',  -- ADR-0008 2값
    confirmed_at          TIMESTAMPTZ,
    version               BIGINT      NOT NULL DEFAULT 0,        -- B6
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A1/A2: 주간 계획 화면 진입·주차 이동의 단일 조회 키
    CONSTRAINT ux_weekly_plan_week UNIQUE (user_id, week_start_date),
    CONSTRAINT ck_weekly_plan_status CHECK (status IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT ck_weekly_plan_range  CHECK (week_start_date < week_end_date)
);

CREATE TABLE schedules (
    schedule_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    title             VARCHAR(200) NOT NULL,
    estimated_minutes INTEGER,                                   -- B2 (PLAN-08/17)
    priority          INTEGER,                                   -- B2
    start_at          TIMESTAMPTZ  NOT NULL,
    end_at            TIMESTAMPTZ  NOT NULL,
    memo              TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version           BIGINT       NOT NULL DEFAULT 0,           -- B6
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_schedules_range     CHECK (start_at < end_at),
    CONSTRAINT ck_schedules_estimated CHECK (estimated_minutes IS NULL OR estimated_minutes % 5 = 0)
);
CREATE INDEX ix_schedules_user_start ON schedules (user_id, start_at);

CREATE TABLE plan_blocks (
    plan_block_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    weekly_plan_id UUID        NOT NULL REFERENCES weekly_plans (weekly_plan_id) ON DELETE CASCADE,
    task_id        UUID        REFERENCES tasks (task_id) ON DELETE CASCADE,
    schedule_id    UUID        REFERENCES schedules (schedule_id) ON DELETE CASCADE,
    block_type     VARCHAR(10) NOT NULL,
    start_at       TIMESTAMPTZ NOT NULL,
    end_at         TIMESTAMPTZ NOT NULL,
    -- tasks.status의 반정규화 미러 (동일 tx 갱신)
    status         VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- title 컬럼 없음 — task/schedule의 title 파생(불일치 원천 제거, 응답에서 조인 제공)
    CONSTRAINT ck_plan_block_type   CHECK (block_type IN ('TASK', 'SCHEDULE')),
    CONSTRAINT ck_plan_block_status CHECK (status IN ('SCHEDULED', 'COMPLETED')),
    CONSTRAINT ck_plan_block_range  CHECK (start_at < end_at),   -- NFR-009
    -- 이중 참조 오용 차단 (data-model-erd §2.2)
    CONSTRAINT ck_plan_block_ref    CHECK (
        (block_type = 'TASK'     AND task_id IS NOT NULL AND schedule_id IS NULL)
     OR (block_type = 'SCHEDULE' AND schedule_id IS NOT NULL AND task_id IS NULL)
    )
);
-- A1: 주간 계획 조립 (fetch join 필수 — N+1 금지)
CREATE INDEX ix_plan_blocks_plan ON plan_blocks (weekly_plan_id);

CREATE TABLE validation_issues (
    validation_issue_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    weekly_plan_id      UUID        NOT NULL REFERENCES weekly_plans (weekly_plan_id) ON DELETE CASCADE,
    plan_block_id       UUID        REFERENCES plan_blocks (plan_block_id) ON DELETE CASCADE,
    task_id             UUID        REFERENCES tasks (task_id) ON DELETE CASCADE,
    wbs_item_id         UUID        REFERENCES wbs_items (wbs_item_id) ON DELETE CASCADE,
    issue_type          VARCHAR(30) NOT NULL,
    severity            VARCHAR(10) NOT NULL,
    message             TEXT        NOT NULL,
    resolution_status   VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- user-stories §2 규칙표 V1~V7과 1:1. V7은 임계값 확정(Q2) 전까지 엔진이 생성하지 않는다.
    CONSTRAINT ck_issue_type CHECK (issue_type IN (
        'V1_OVERLAP', 'V2_FIXED_CONFLICT', 'V3_CAPACITY_EXCEEDED', 'V4_OUT_OF_AVAILABILITY',
        'V5_OUT_OF_WBS', 'V6_AFTER_DUE_DATE', 'V7_BUFFER_SHORTAGE')),
    CONSTRAINT ck_issue_severity   CHECK (severity IN ('BLOCK', 'WARNING')),
    CONSTRAINT ck_issue_resolution CHECK (resolution_status IN ('OPEN', 'RESOLVED'))
);
-- A11: 검증 이슈 목록 (PLAN-22)
CREATE INDEX ix_validation_issues_plan ON validation_issues (weekly_plan_id, resolution_status);

CREATE TABLE replan_options (
    replan_option_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    weekly_plan_id       UUID        NOT NULL REFERENCES weekly_plans (weekly_plan_id) ON DELETE CASCADE,
    validation_issue_id  UUID        REFERENCES validation_issues (validation_issue_id) ON DELETE CASCADE,
    strategy_type        VARCHAR(30) NOT NULL,
    change_summary       TEXT,
    recommendation_reason TEXT,
    score                NUMERIC(5,2),
    proposed_blocks      JSONB       NOT NULL,                   -- 대안의 블록 배치안 스냅샷 (선택 시 적용 원본)
    is_selected          BOOLEAN     NOT NULL DEFAULT false,     -- SC-07: D-019 대체 (재계획 실행 기록)
    selected_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- KEEP_CURRENT는 기준선이라 행을 만들지 않는다 (user-stories §3.1)
    CONSTRAINT ck_replan_strategy CHECK (strategy_type IN ('MINIMAL_CHANGE', 'DEADLINE_FIRST', 'WORKLOAD_BALANCE'))
);
CREATE INDEX ix_replan_options_plan ON replan_options (weekly_plan_id);


-- =====================================================================================
-- 6. 수행 기록  (BE-2)
-- =====================================================================================

CREATE TABLE execution_logs (
    execution_log_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    -- PROJ-09 "연관 데이터가 제거된다" 명시 준수 (data-model-erd §4 삭제 일관성)
    task_id          UUID        NOT NULL REFERENCES tasks (task_id) ON DELETE CASCADE,
    plan_block_id    UUID        REFERENCES plan_blocks (plan_block_id) ON DELETE SET NULL,
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ,
    actual_minutes   INTEGER,
    result           VARCHAR(20) NOT NULL,
    memo             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_exec_result CHECK (result IN ('COMPLETED', 'DELAYED', 'ABORTED')),
    CONSTRAINT ck_exec_range  CHECK (ended_at IS NULL OR started_at <= ended_at),
    CONSTRAINT ck_exec_actual CHECK (actual_minutes IS NULL
                                     OR (actual_minutes % 5 = 0 AND actual_minutes > 0))
);
-- A6/A7: 편차 통계·구간 패턴 (B9 — execution_stats 미생성, 온더플라이 집계)
CREATE INDEX ix_exec_logs_user_started ON execution_logs (user_id, started_at);
CREATE INDEX ix_exec_logs_task         ON execution_logs (task_id);


-- =====================================================================================
-- 7. 알림 · 지원 · 공지  (BE-1)
-- =====================================================================================

CREATE TABLE notification_settings (
    notification_setting_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    notification_type       VARCHAR(30) NOT NULL,
    is_enabled              BOOLEAN     NOT NULL DEFAULT true,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_noti_setting UNIQUE (user_id, notification_type),
    CONSTRAINT ck_noti_setting_type CHECK (notification_type IN (
        'DEADLINE_SOON', 'TODAY_TASKS', 'PLAN_UNSAVED', 'RETROSPECT', 'SUPPORT_ANSWERED'))
);

-- notifications보다 먼저 — related_support_ticket_id FK 대상.
CREATE TABLE support_tickets (
    support_ticket_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    category          VARCHAR(30)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    content           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    answer_content    TEXT,                                       -- 답변 전 NULL (HELP-02 hasAnswer 재료)
    answered_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 논리모델 '접수/처리중/답변완료/종료'의 영문 확정 (§5 사전 미수록 — 레인 로컬)
    CONSTRAINT ck_ticket_status CHECK (status IN ('RECEIVED', 'IN_PROGRESS', 'ANSWERED', 'CLOSED'))
);
-- A12: 문의 목록 최신순 (HELP-01)
CREATE INDEX ix_support_tickets_user ON support_tickets (user_id, created_at DESC);

CREATE TABLE notifications (
    notification_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    notification_setting_id  UUID         NOT NULL REFERENCES notification_settings (notification_setting_id) ON DELETE CASCADE,
    related_task_id          UUID         REFERENCES tasks (task_id) ON DELETE CASCADE,
    related_weekly_plan_id   UUID         REFERENCES weekly_plans (weekly_plan_id) ON DELETE CASCADE,
    -- NFR-030(본인 문의만)의 무결성 근거
    related_support_ticket_id UUID        REFERENCES support_tickets (support_ticket_id) ON DELETE CASCADE,
    notification_type        VARCHAR(30)  NOT NULL,
    read_at                  TIMESTAMPTZ,
    route_path               VARCHAR(255),                        -- NOTI-04 클릭 이동 경로
    is_active                BOOLEAN      NOT NULL DEFAULT true,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_noti_type CHECK (notification_type IN (
        'DEADLINE_SOON', 'TODAY_TASKS', 'PLAN_UNSAVED', 'RETROSPECT', 'SUPPORT_ANSWERED'))
);
-- A8: 알림 센터 최신순 + 미읽음 수 (partial)
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread       ON notifications (user_id) WHERE read_at IS NULL;

-- 사용자 무소속 공개 콘텐츠 ①. 콘텐츠는 Flyway 시드로 주입 (관리 UI는 US에 없음 — ERD §7).
CREATE TABLE help_articles (
    help_article_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    category        VARCHAR(50)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,
    keywords        TEXT,                                         -- HELP-06 검색용
    target_audience VARCHAR(30),
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_help_status CHECK (status IN ('PUBLISHED', 'HIDDEN'))
);
CREATE INDEX ix_help_articles_category ON help_articles (category, sort_order);

-- 사용자 무소속 공개 콘텐츠 ②.
CREATE TABLE announcements (
    announcement_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- ⚠️ ERD 표에 없는 추가 컬럼. 근거: OPS-01 "목록(**유형**·제목·게시일)" +
    --    ST-B1-14 AC③ "공지 유형 Badge 재료(점검/장애/변경) enum 고정". 컬럼 없이는 US 미충족.
    --    → be1-notes.md에 baseline 추가분으로 기록, 1주차 설계동결 게이트에서 James 확인 대상.
    announcement_type   VARCHAR(20)  NOT NULL,
    title               VARCHAR(200) NOT NULL,
    content             TEXT         NOT NULL,
    published_start_at  TIMESTAMPTZ  NOT NULL,
    published_end_at    TIMESTAMPTZ,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcement_type   CHECK (announcement_type IN ('MAINTENANCE', 'INCIDENT', 'CHANGE')),
    CONSTRAINT ck_announcement_status CHECK (status IN ('PUBLISHED', 'ENDED', 'HIDDEN')),
    CONSTRAINT ck_announcement_window CHECK (published_end_at IS NULL OR published_start_at < published_end_at)
);
-- OPS-01: 게시일 DESC 목록
CREATE INDEX ix_announcements_published ON announcements (published_start_at DESC);


-- =====================================================================================
-- 8. 지연 FK — 순환 참조 회피
-- =====================================================================================

-- B13 (GAP-1) · TUT-09: 실 FK 채택. 튜토리얼이 삭제를 실습(TUT-07)하므로
-- SET NULL로 "잔존 여부"가 컬럼 하나로 판정된다 (non-null = 잔존).
ALTER TABLE onboarding_progress
    ADD CONSTRAINT fk_onboarding_tutorial_project
    FOREIGN KEY (tutorial_sample_project_id) REFERENCES projects (project_id) ON DELETE SET NULL;
