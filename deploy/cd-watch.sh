#!/usr/bin/env bash
# OpenPlan — CD 감시자. systemd 타이머가 주기적으로 부른다.
#
# 하는 일: 세 저장소의 원격 SHA 를 읽어 배포된 것과 다르면 bootstrap.sh 를 돌린다.
#          끝나면 무엇이 언제 배포됐는지를 web/deploy-status.json 에 남긴다.
#
# 왜 서버가 당기는(pull) 방식인가 — GitHub Actions 에서 SSH 로 미는 방식이 아니라:
#   1. SSH 22 번이 개발 PC 공인 IP /32 만 허용한다. Actions 러너는 IP 가 매번 다르므로
#      GitHub IP 대역 전체(수백 개, 수시 변경) 또는 0.0.0.0/0 을 열어야 한다 —
#      배포 서버의 SSH 를 전 세계에 여는 대가로 얻는 편의다. 마감 D-4 에 할 거래가 아니다.
#   2. 미는 방식은 배포 개인키를 GitHub Secrets 에 넣어야 하는데, D-64 가 정확히 그것을
#      경고한다 — 시크릿은 주입 시점에 검증되지 않는다. 개행이 여러 줄인 PEM 은
#      붙여넣기가 망가져도 `✓` 가 찍히고 첫 배포에서야 드러난다.
#   3. 당기는 방식은 인바운드가 0 이고 서버 밖으로 나가는 자격증명도 0 이다.
#      저장소 셋이 전부 public 이라 토큰조차 필요 없다.
#
# 🔴 이 스크립트는 배포를 "따라잡게" 할 뿐, 무엇을 배포할지 정하지 않는다.
#    대상 브랜치는 cd.conf 가 정한다 — 기억이 아니라 파일이 정본이다.

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/openplan}"
CONF="${CD_CONF:-$APP_DIR/cd.conf}"
STATE="$APP_DIR/.cd-state"
LOCK="$APP_DIR/.cd.lock"
LOG_DIR="$APP_DIR/logs"
# bootstrap.sh 의 DOCKER="sudo docker" 와 같은 손잡이. 테스트에서 SUDO="" 로 비운다.
SUDO="${SUDO-sudo}"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '%s  %s\n' "$(ts)" "$*"; }

# ── 0. 설정 ─────────────────────────────────────────────────────────────────
if [ ! -f "$CONF" ]; then
  log "🔴 설정이 없다: $CONF — deploy/cd.conf.example 을 복사해 채울 것"
  exit 1
fi
# shellcheck disable=SC1090
. "$CONF"

: "${BE_BRANCH:?cd.conf 에 BE_BRANCH 가 없다}"
: "${FE_BRANCH:?cd.conf 에 FE_BRANCH 가 없다}"
CD_DRY_RUN="${CD_DRY_RUN:-0}"
# 🔴 AI 도 추적한다. 위 주석이 "AI_* 는 일부러 안 읽는다" 였고 근거는 세 가지였는데
#    (bootstrap 이 AI 를 sync_repo 하지 않는다 · compose 에 ai 서비스가 없다 ·
#    AI 는 배포된 적이 없다), #59 병합 이후 **셋 다 사실이 아니다**. 그 주석이 지키려던
#    원칙("추적과 배포가 따로 놀지 않게")은 이제 정반대를 요구한다 — 배포하는데
#    추적하지 않으면 AI 저장소만 바뀐 변경이 영원히 서버에 안 올라간다.
#
# 🔴 없으면 죽이지 않고 기본값을 준다. `: "${AI_BRANCH:?}"` 로 필수화하면 이 파일보다
#    먼저 만들어진 cd.conf(AI_BRANCH 가 없다)를 쓰는 서버에서 **매 회차 즉사**한다 —
#    타이머는 계속 도는데 배포는 영원히 안 되고, 밖에서는 조용하기만 하다(D-63).
#    기본값은 bootstrap.sh 의 것과 같게 둔다. 그래야 추적과 배포가 어긋나지 않는다.
AI_BRANCH="${AI_BRANCH:-main}"
AI_REPO="${AI_REPO:-https://github.com/Seeds-OpenPlan/OpenPlan-AI.git}"

mkdir -p "$LOG_DIR"

# ── 1. 잠금 ─────────────────────────────────────────────────────────────────
# 🔴 t3.medium 은 RAM 4GB(실측 3.7GB)에 스왑 2GB 다. Gradle 데몬 + Node 빌드가
#    한 번에도 빠듯한데 두 배포가 겹치면 OOM Killer 가 컴파일 도중 프로세스를 죽인다.
#    로그에는 원인이 명확히 안 남는다("daemon disappeared unexpectedly" 정도).
#    그래서 겹치면 기다리지 않고 그냥 건너뛴다 — 다음 타이머가 어차피 다시 온다.
exec 9>"$LOCK"
if ! flock -n 9; then
  log "이미 배포가 돌고 있다 — 이번 회차는 건너뛴다"
  exit 0
fi

# ── 2. 원격 SHA ─────────────────────────────────────────────────────────────
# 🔴 ls-remote 는 없는 브랜치에 대해 오류가 아니라 **빈 출력**을 준다.
#    빈 값을 그대로 비교하면 "바뀌었다"로 읽혀 매 회차 배포가 돈다. 반드시 막는다.
#    (D-71 — 조회 실패와 결과 없음은 다른 사실인데 둘 다 빈 출력으로 보인다.)
remote_sha() {  # $1=저장소 $2=브랜치
  local sha
  sha="$(git ls-remote "$1" "refs/heads/$2" 2>/dev/null | cut -f1)"
  if [ -z "$sha" ]; then
    # 🔴 stderr 로 낸다. 이 함수는 명령 치환($(...))으로 불리므로 stdout 에 쓰면
    #    메시지가 SHA 변수 안으로 빨려 들어가 화면에는 아무것도 안 남는다 —
    #    운영자는 이유 없는 rc=1 만 본다(D-63, 미실행의 침묵).
    log "🔴 원격에서 브랜치를 못 찾았다: $1 $2" >&2
    return 1
  fi
  printf '%s' "$sha"
}

BE_SHA="$(remote_sha "$BE_REPO" "$BE_BRANCH")"
FE_SHA="$(remote_sha "$FE_REPO" "$FE_BRANCH")"
# 🔴 AI 는 없어도 서비스가 도는 부품이다(Spring 이 규칙 폴백한다). 조회가 실패해도
#    BE·FE 배포까지 끌고 내려가지 않는다 — bootstrap.sh 가 AI_READY 로 같은 방침을
#    쓰므로 여기서만 엄격하면 어긋난다.
AI_SHA="$(remote_sha "$AI_REPO" "$AI_BRANCH" || printf 'unknown')"

WANT="$BE_BRANCH@$BE_SHA $FE_BRANCH@$FE_SHA $AI_BRANCH@$AI_SHA"
HAVE="$(cat "$STATE" 2>/dev/null || printf '(최초)')"

if [ "${FORCE:-0}" != 1 ] && [ "$WANT" = "$HAVE" ]; then
  log "변경 없음 — $WANT"
  exit 0
fi

log "변경 감지"
log "  이전: $HAVE"
log "  이후: $WANT"

if [ "$CD_DRY_RUN" = 1 ]; then
  log "CD_DRY_RUN=1 — 배포하지 않고 끝낸다"
  exit 0
fi

# ── 3. 배포 ─────────────────────────────────────────────────────────────────
RUN_LOG="$LOG_DIR/cd-$(date -u +%Y%m%dT%H%M%SZ).log"
log "배포 시작 → $RUN_LOG"
START=$(date +%s)

# 🔴 bootstrap.sh 를 고치지 않고 환경변수로만 부른다. 지금 열려 있는 PR 8건 중
#    #38 이 바로 그 파일을 건드리고 있어, 여기서 같이 고치면 충돌한다.
#    CD 는 새 파일만으로 성립해야 한다.
set +e
BE_REPO="$BE_REPO" FE_REPO="$FE_REPO" AI_REPO="$AI_REPO" \
BE_BRANCH="$BE_BRANCH" FE_BRANCH="$FE_BRANCH" AI_BRANCH="$AI_BRANCH" \
APP_DIR="$APP_DIR" \
  bash "$APP_DIR/deploy/bootstrap.sh" >>"$RUN_LOG" 2>&1
RC=$?
set -e
ELAPSED=$(( $(date +%s) - START ))

# ── 4. 배포 스탬프 ──────────────────────────────────────────────────────────
# 🔴 이 스탬프가 CD 자체만큼 값이 있다. 2026-08-24 콜드스타트에서 "배포본이 최신인가"를
#    알아내려고 번들 바이트 수를 세고 password123 을 grep 해야 했다. 머지는 됐는데
#    배포가 안 된 상태를 밖에서 볼 방법이 없었기 때문이다.
#    앞으로는 curl https://openplan.services/deploy-status.json 한 번이면 된다.
#
# 🔴 로그 내용은 넣지 않는다 — docker build 출력에 무엇이 섞일지 보장할 수 없다.
#    공개되는 파일에는 브랜치·SHA·시각만 둔다(저장소가 public 이라 새로 새는 정보가 없다).
#
# 🔴 web/ 는 bootstrap 이 sudo 로 채워 root 소유다. 일반 사용자로 쓰면 조용히 실패한다
#    (D-72 — 파일 검사는 존재가 아니라 접근 가능 여부를 답한다).
STATUS_OK=$([ $RC -eq 0 ] && echo true || echo false)

# 🔴 스탬프 기록은 절대 배포 판정을 뒤집지 못하게 한다.
#    초안에서 이 줄이 `set -e` 아래 그냥 놓여 있었고, 테스트에서 tee 가 실패하자
#    **배포가 성공(rc=0)했는데도 스크립트 전체가 실패로 끝나고 상태 파일이 안 써졌다.**
#    그러면 다음 회차가 같은 것을 또 배포한다 — 5분마다 전체 재빌드가 무한 반복되고
#    4GB 박스가 스스로 무너진다. 부수 기록이 본 경로를 죽여서는 안 된다.
if ! $SUDO tee "$APP_DIR/web/deploy-status.json" >/dev/null <<JSON
{
  "deployedAt": "$(ts)",
  "ok": $STATUS_OK,
  "durationSeconds": $ELAPSED,
  "backend":  { "branch": "$BE_BRANCH", "sha": "$BE_SHA" },
  "frontend": { "branch": "$FE_BRANCH", "sha": "$FE_SHA" },
  "ai":       { "branch": "$AI_BRANCH", "sha": "$AI_SHA" },
  "log": "$(basename "$RUN_LOG")"
}
JSON
then
  log "⚠️ 배포 스탬프를 쓰지 못했다(web/ 권한?) — 배포 판정에는 영향 없음"
fi

if [ $RC -ne 0 ]; then
  # 🔴 실패했으면 상태를 갱신하지 않는다 — 그래야 다음 회차가 다시 시도한다.
  #    갱신해 버리면 "한 번 실패하고 영원히 조용한" 상태가 된다(D-63, 미실행의 침묵).
  log "🔴 배포 실패 (rc=$RC, ${ELAPSED}s) — 상태를 갱신하지 않는다. 로그: $RUN_LOG"
  tail -20 "$RUN_LOG" || true
  exit $RC
fi

printf '%s' "$WANT" > "$STATE"
log "🟢 배포 완료 (${ELAPSED}s) — $WANT"

# 오래된 로그는 30개만 남긴다. 8GB 루트 볼륨이라 방치하면 언젠가 찬다.
ls -1t "$LOG_DIR"/cd-*.log 2>/dev/null | tail -n +31 | xargs -r rm -f
