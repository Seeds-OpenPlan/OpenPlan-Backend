-- =====================================================================================
-- ST-B1-11 — 제공자 OAuth 토큰 보관소 (BE-1)
--
-- 배경: 설계에 **제공자 토큰을 담을 자리가 없었다.** external_calendar_connections 에는
--       토큰 컬럼이 없고, auth_tokens 는 EXTERNAL_AUTH 타입을 CHECK 에 갖고 있어 자리가
--       있는 것처럼 보이지만 실제로는 쓸 수 없다 —
--
--         V202608101930 이 auth_tokens 에 넣은 것은 token_hash, 즉 **단방향 해시**다.
--         그 테이블은 "제출된 값이 맞는지 대조하고 1회 쓰고 버리는"(used_at, NFR-005)
--         이메일 인증·비밀번호 재설정 링크용이다.
--
--       캘린더는 정반대다. 제공자 API 를 계속 호출해야 하므로 access/refresh 토큰을
--       **원문으로 되읽어야** 한다. 해시로는 성립하지 않는다.
--
-- 결정(사용자 확정): 연결 행에 **암호문 컬럼**으로 둔다. 별도 테이블로 분리하지 않는다 —
--       토큰의 수명이 연결의 수명과 정확히 같아서, FIX-17 해제 시 별도 CASCADE 없이
--       같은 행이 사라진다. 조인도 늘지 않는다.
--
-- 암호화: AES-GCM. 키는 EXT_TOKEN_KEY 환경변수로 주입한다(SMTP·JWT 자격증명과 같은 경로).
--       컬럼에는 base64(nonce || ciphertext || tag) 를 싣는다. 길이가 제공자·스코프에 따라
--       달라지므로 VARCHAR 상한을 두지 않고 TEXT 로 둔다.
--       평문 저장을 택하지 않은 이유: DB 유출이 곧 **사용자의 구글·네이버·카카오 캘린더
--       접근권 유출**이 된다. 배포 서버가 아직 평문 HTTP 라 위험이 겹친다.
--
-- NULL 성:
--   access_token_enc  NOT NULL — 연결 행의 생성 조건이 곧 authCode 교환 성공이다.
--                     나중에 nullable 로 두면 토큰 없는 연결이 조용히 쌓이는 경로가 남는다.
--                     (기존 행 0건이라 NOT NULL 을 바로 걸 수 있다 — 쓰는 코드가 아직 없다.)
--   refresh_token_enc NULL 허용 — 제공자·동의 조건에 따라 발급되지 않을 수 있다.
--   token_expires_at  NULL 허용 — 만료를 알려주지 않는 제공자가 있다.
-- =====================================================================================

ALTER TABLE external_calendar_connections
    ADD COLUMN access_token_enc  TEXT        NOT NULL,
    ADD COLUMN refresh_token_enc TEXT,
    ADD COLUMN token_expires_at  TIMESTAMPTZ;

COMMENT ON COLUMN external_calendar_connections.access_token_enc IS
    '제공자 access token 의 AES-GCM 암호문 base64(nonce||ct||tag). 키는 EXT_TOKEN_KEY. 평문은 저장하지 않는다.';
COMMENT ON COLUMN external_calendar_connections.refresh_token_enc IS
    '제공자 refresh token 암호문. 제공자가 발급하지 않으면 NULL — 그 경우 만료 시 재연동이 필요하다.';
COMMENT ON COLUMN external_calendar_connections.token_expires_at IS
    'access token 만료 시각. 이 시각 이전이면 갱신 없이 사용한다. 제공자가 알려주지 않으면 NULL.';
