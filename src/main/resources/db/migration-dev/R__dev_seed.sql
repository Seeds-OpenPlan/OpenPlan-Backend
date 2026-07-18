-- =====================================================================================
-- dev 시드 (D-16 · ADR-0002 §4) — **dev/local 프로파일 전용**
--
-- 위치가 곧 안전장치: 이 파일은 `db/migration-dev/`에 있고, 운영 프로파일의
-- `spring.flyway.locations`에는 이 경로가 없다 → 운영 유출을 구조적으로 차단한다
-- (ST-B1-01 AC2). 경로를 옮기지 말 것.
--
-- 목적: 로그인(4주차) 없이 스코핑된 채 도메인을 개발한다. DevUserAuthFilter가
--       X-Dev-User 헤더로 여기 시드된 사용자를 주입한다 (api-contracts §4.1).
--
-- 소유 경계: BE-1 레인 데이터만 시드한다(사용자·프로필·기본설정·온보딩·가용시간·알림설정).
--            프로젝트/태스크/주간계획 등 BE-2 도메인 시드는 각 레인이 자기 마이그레이션에서 넣는다.
--
-- R__ = repeatable. 파일이 바뀔 때마다 재실행되므로 **전 구문이 멱등**해야 한다.
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- 사용자 2명 — 2명인 이유: NFR-030(사용자 격리)·404 은닉을 실제로 테스트하려면
-- "남" 역할이 필요하다. 1명이면 타인 리소스 접근을 재현할 수 없다.
-- ---------------------------------------------------------------------------
INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'dev1@openplan.local',
     -- 실제 로그인용이 아니다: 인증 EP는 4주차까지 501 E-AUTH-011. 컬럼 NOT NULL 충족용 자리채움.
     '$2a$10$devseedplaceholderhashdevseedplaceholderhashdevseedpl', 'LOCAL', true, 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000002', 'dev2@openplan.local',
     '$2a$10$devseedplaceholderhashdevseedplaceholderhashdevseedpl', 'LOCAL', true, 'ACTIVE')
ON CONFLICT (user_id) DO UPDATE
    SET email             = EXCLUDED.email,
        is_email_verified = EXCLUDED.is_email_verified,
        status            = EXCLUDED.status;

INSERT INTO user_profiles (profile_id, user_id, name, purpose, timezone, week_start_day)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     '개발용 사용자1', '학업 일정 관리', 'Asia/Seoul', 'MON'),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
     '개발용 사용자2', '취업 준비', 'Asia/Seoul', 'MON')
ON CONFLICT (user_id) DO UPDATE
    SET name           = EXCLUDED.name,
        purpose        = EXCLUDED.purpose,
        timezone       = EXCLUDED.timezone,
        week_start_day = EXCLUDED.week_start_day;

-- FIX-10~12 기본값 — 미설정(NULL) 상태도 유효하므로 1번만 값을 넣고 2번은 비운다.
-- "기본값 없음" 경로(RB-FIX-01 제안 노출)도 개발 중 재현 가능해야 한다.
INSERT INTO user_preferences (user_id, default_estimated_minutes, default_replan_strategy)
VALUES
    ('00000000-0000-0000-0000-000000000001', 60, 'MINIMAL_CHANGE'),
    ('00000000-0000-0000-0000-000000000002', NULL, NULL)
ON CONFLICT (user_id) DO UPDATE
    SET default_estimated_minutes = EXCLUDED.default_estimated_minutes,
        default_replan_strategy   = EXCLUDED.default_replan_strategy,
        updated_at                = now();

-- 온보딩 **완료** 상태 (ST-B1-01) — 도메인 개발 시 온보딩 리다이렉트에 매번 막히지 않게.
-- 온보딩 화면 자체를 개발할 때는 이 행을 UPDATE로 false로 되돌려 재현한다.
INSERT INTO onboarding_progress (user_id, intro_done, profile_done, availability_done,
                                 fixed_schedule_done, tutorial_done, calendar_sync_done)
VALUES
    ('00000000-0000-0000-0000-000000000001', true, true, true, true, true, true),
    ('00000000-0000-0000-0000-000000000002', true, true, true, true, true, true)
ON CONFLICT (user_id) DO UPDATE
    SET intro_done          = EXCLUDED.intro_done,
        profile_done        = EXCLUDED.profile_done,
        availability_done   = EXCLUDED.availability_done,
        fixed_schedule_done = EXCLUDED.fixed_schedule_done,
        tutorial_done       = EXCLUDED.tutorial_done,
        calendar_sync_done  = EXCLUDED.calendar_sync_done;

-- 가용 시간 7행 (ONB-04 · FIX-01~03). 평일 09:00–18:00 / 주말 10:00–16:00 — 전부 5분 배수.
-- 주간 계획 검증(V3 용량·V4 가용시간 밖)이 첫날부터 의미 있는 값을 갖도록 시드한다.
INSERT INTO availability_patterns (availability_pattern_id, user_id, weekday, start_time, end_time, is_active)
VALUES
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'MON', '09:00', '18:00', true),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'TUE', '09:00', '18:00', true),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'WED', '09:00', '18:00', true),
    ('20000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'THU', '09:00', '18:00', true),
    ('20000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001', 'FRI', '09:00', '18:00', true),
    ('20000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001', 'SAT', '10:00', '16:00', true),
    ('20000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000001', 'SUN', '10:00', '16:00', true)
ON CONFLICT (user_id, weekday) DO UPDATE
    SET start_time = EXCLUDED.start_time,
        end_time   = EXCLUDED.end_time,
        is_active  = EXCLUDED.is_active;

-- 알림 설정 5종 (NOTI-01). 기본 전체 ON — 꺼진 유형은 발송(행 생성) 자체가 없다(ST-B1-12 AC1).
INSERT INTO notification_settings (user_id, notification_type, is_enabled)
SELECT u.user_id, t.notification_type, true
FROM (VALUES
        ('00000000-0000-0000-0000-000000000001'::UUID),
        ('00000000-0000-0000-0000-000000000002'::UUID)
     ) AS u(user_id)
CROSS JOIN (VALUES
        ('DEADLINE_SOON'), ('TODAY_TASKS'), ('PLAN_UNSAVED'), ('RETROSPECT'), ('SUPPORT_ANSWERED')
     ) AS t(notification_type)
ON CONFLICT (user_id, notification_type) DO UPDATE
    SET is_enabled = EXCLUDED.is_enabled,
        updated_at = now();
