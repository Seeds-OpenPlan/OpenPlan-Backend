#!/usr/bin/env bash
# OpenPlan — EC2(Amazon Linux 2023) 1차 배포 부트스트랩
#
# 하는 일: Docker·git·Node 설치 → 저장소 2개 클론 → FE 빌드 → BE 이미지 빌드 → 기동.
#
# 사용:
#   ssh -i ~/.ssh/openplan-key.pem ec2-user@<EC2_PUBLIC_IP>
#   curl -fsSL <이 파일 raw URL> -o bootstrap.sh && bash bootstrap.sh
#   또는 저장소를 먼저 클론했다면: bash deploy/bootstrap.sh
#
# 이 스크립트는 2026-08-17 EC2 1차 배포에서 실행·검증됐다. 그때 드러난 결함 5건
# (브랜치 미교체 · MAIL_* 미검사 · MAIL_FROM 오판 · buildx 미갱신 · web/ 삭제)은
# 아래에 각각 주석과 함께 반영돼 있다.
#
# 🔴 .env 는 이 스크립트가 만들지 않는다. 비밀값이 들어가므로 사람이 직접 채운다.

set -euo pipefail

BE_REPO="${BE_REPO:-https://github.com/Seeds-OpenPlan/OpenPlan-Backend.git}"
FE_REPO="${FE_REPO:-https://github.com/Seeds-OpenPlan/OpenPlan-Frontend.git}"
# 인증 실구현(#22·#23, 08-18)과 배포 파일(#31, 08-23)이 모두 main 에 있다. 통합용이던
# deploy/auth-integration 은 역할이 끝났다 — 그 브랜치는 4e34731(08-17)에서 멈춰 있어
# 기본값으로 두면 재배포해도 두 주 묵은 코드가 다시 올라간다(서버가 안 바뀌는 원인).
BE_BRANCH="${BE_BRANCH:-main}"
FE_BRANCH="${FE_BRANCH:-main}"
# AI 서비스는 별도 저장소다(파이썬). docker-compose.prod.yml 이 ../openplan-ai 를 빌드 컨텍스트로
# 참조하므로 APP_DIR 의 형제 자리에 받아야 한다 — 경로가 어긋나면 compose 가 빈 곳을 굽는다.
AI_REPO="${AI_REPO:-https://github.com/Seeds-OpenPlan/OpenPlan-AI.git}"
AI_BRANCH="${AI_BRANCH:-main}"
APP_DIR="${APP_DIR:-$HOME/openplan}"
FE_DIR="$HOME/openplan-fe"
# 🔴 APP_DIR 의 **형제 자리**여야 한다. docker-compose.prod.yml 의
#    openplan-ai.build.context 가 `../openplan-ai` 라 compose 파일 위치(=APP_DIR) 기준
#    상대경로로 풀리는데, 여기를 $HOME 에 고정하면 APP_DIR 을 옮긴 순간 둘이 어긋나
#    **compose 가 없는 디렉터리를 굽는다**(2026-08-27 리뷰 지적).
AI_DIR="$(dirname "$APP_DIR")/openplan-ai"

log() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

# ── 1. 패키지 ────────────────────────────────────────────────────────────────
log "1/6 패키지 설치 (docker · git)"
# 🔴 nodejs 를 여기서 깔지 않는다. AL2023 저장소의 nodejs 는 18인데 프론트가
#    package.json engines 로 ">=22.12.0 <23" 를 요구한다(Vite 8). 호스트에 22를
#    따로 얹는 대신 5단계에서 node:22-alpine 컨테이너로 빌드한다 — 버전이 고정되고
#    호스트가 더러워지지 않는다.
sudo dnf -y update
sudo dnf -y install docker git

# ── 1-b. 스왑 ────────────────────────────────────────────────────────────────
# t3.medium 은 RAM 4GB(실측 3.7GB)가 상한이다. Gradle 데몬 + Node 빌드가 겹치면
# OOM Killer 가 컴파일 도중 프로세스를 죽이는데, 로그에는 원인이 명확히 안 남는다
# ("Gradle build daemon disappeared unexpectedly" 정도). 스왑 2GB로 받친다.
if ! swapon --show | grep -q .; then
  log "1b/6 스왑 2GB 생성"
  sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi
free -h | head -3

# ── 2. Docker ────────────────────────────────────────────────────────────────
log "2/6 Docker 기동 + 권한"
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

# compose v2 플러그인 — AL2023 기본 저장소에 없어 바이너리를 직접 놓는다.
if ! docker compose version >/dev/null 2>&1; then
  log "     compose 플러그인 설치"
  CLI_DIR=/usr/libexec/docker/cli-plugins
  sudo install -d "$CLI_DIR"
  # /tmp 에 받아서 install 로 옮긴다. 목적지에 직접 받은 뒤 같은 경로로 install 하면
  # "are the same file" 로 죽는다(실측).
  curl -fsSL \
    "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
    -o /tmp/docker-compose
  sudo install -m 0755 /tmp/docker-compose "$CLI_DIR/docker-compose"
fi

# 🔴 buildx — compose 만 최신으로 올리고 buildx 를 두면 6단계가 죽는다(2026-08-17 실측):
#   "compose build requires buildx 0.17.0 or later"
# AL2023 의 buildx 는 0.12.1 인데 위에서 받는 compose 최신본이 0.17+ 를 요구한다.
# 프론트 빌드까지 다 끝난 뒤 마지막 줄에서 터지므로 낭비가 크다 — 여기서 미리 맞춘다.
#
# 최신 태그를 API 로 물어본 뒤 받는다. compose 쪽이 쓰는 `releases/latest/download/`
# 형식은 buildx 에는 통하지 않는다 — 자산 이름에 버전이 박혀 있어 404 가 난다(실측).
NEED_BUILDX=0.17.0
HAVE_BUILDX="$(docker buildx version 2>/dev/null | grep -oE 'v?[0-9]+\.[0-9]+\.[0-9]+' | head -1 | tr -d v)"
if [ -z "$HAVE_BUILDX" ] || [ "$(printf '%s\n%s\n' "$NEED_BUILDX" "$HAVE_BUILDX" | sort -V | head -1)" != "$NEED_BUILDX" ]; then
  log "     buildx 갱신 (현재 ${HAVE_BUILDX:-없음} → ${NEED_BUILDX}+ 필요)"
  CLI_DIR=/usr/libexec/docker/cli-plugins
  sudo install -d "$CLI_DIR"
  BX_TAG="$(curl -fsSL https://api.github.com/repos/docker/buildx/releases/latest \
            | grep -m1 '"tag_name"' | cut -d'"' -f4)"
  case "$(uname -m)" in x86_64) BX_ARCH=amd64;; aarch64) BX_ARCH=arm64;; *) BX_ARCH="$(uname -m)";; esac
  curl -fsSL "https://github.com/docker/buildx/releases/download/${BX_TAG}/buildx-${BX_TAG}.linux-${BX_ARCH}" \
    -o /tmp/docker-buildx
  sudo install -m 0755 /tmp/docker-buildx "$CLI_DIR/docker-buildx"
fi

# usermod 는 다음 로그인부터 적용된다. 이번 세션은 sudo 로 docker 를 부른다.
DOCKER="sudo docker"

# ── 3. 저장소 ────────────────────────────────────────────────────────────────
log "3/6 저장소 클론/갱신"
# 🔴 `git pull --ff-only` 만 돌리면 이미 클론된 저장소는 "받아둔 그 브랜치"에 계속
#    머문다 — BE_BRANCH 를 바꿔도 반영되지 않는다. 1차 배포가 feat/deploy-compose 로
#    이뤄졌으므로, 브랜치를 갈아타는 재배포가 반드시 필요하다. fetch → switch 로
#    BE_BRANCH 를 매번 실제로 따르게 한다(얕은 클론이라 FETCH_HEAD 를 시작점으로 쓴다).
#    .env 는 추적 대상이 아니라 브랜치를 갈아타도 그대로 남는다.
#
# 🔴 로컬 수정이 있으면 switch 가 거부하고 배포가 그 자리에서 멈춘다(2026-08-23 실측 — 08-17 에
#    서버에서 손으로 때운 FE 파일 하나 때문에 재배포가 중단됐다). 그렇다고 --force 로 밀면
#    살아 있는 핫픽스가 조용히 사라진다. 그래서 지우지 않고 stash 로 옮긴 뒤 진행한다 —
#    사라지지 않고, 멈추지도 않는다. `git -C <dir> stash list` 로 확인할 수 있다.
sync_repo() {  # $1=디렉터리 $2=저장소 $3=브랜치
  if [ -d "$1/.git" ]; then
    if [ -n "$(git -C "$1" status --porcelain)" ]; then
      echo "  ⚠️  $1 에 로컬 수정이 있어 stash 로 옮긴다 (git -C $1 stash list 로 확인)"
      # 🔴 보관 실패가 배포를 죽이면 안 된다(D-76). 2026-08-24 실측: 08-23 HTTPS 전환으로
      #    생긴 root 소유 certbot/conf/ 를 `stash push -u` 가 지우지 못해 exit 1 을 냈고,
      #    set -euo pipefail 아래에서 **배포가 3단계에서 통째로 즉사**했다. 그날 이후 모든
      #    재배포가 조용히 실패했는데, 컨테이너는 멀쩡히 떠 있어 화면으로는 안 보였다.
      #    보관은 곁다리다 — 실패해도 알리고 계속한다. 판정은 아래 fetch/switch 가 한다.
      if ! git -C "$1" stash push -u -m "bootstrap $(date -u +%Y-%m-%dT%H:%M:%SZ) 자동 보관"; then
        echo "  ⚠️  $1 보관 실패 — 배포는 계속한다. 남은 로컬 수정은 아래 switch 가 판정한다" >&2
      fi
    fi
    git -C "$1" fetch --depth 1 origin "$3"
    git -C "$1" switch -C "$3" FETCH_HEAD
  else
    git clone --depth 1 -b "$3" "$2" "$1"
  fi
}
sync_repo "$APP_DIR" "$BE_REPO" "$BE_BRANCH"
sync_repo "$FE_DIR"  "$FE_REPO" "$FE_BRANCH"
# 🔴 AI 만 실패를 허용한다. set -euo pipefail 아래에서 이 줄이 그냥 실패하면 FE 빌드도
#    backend 기동도 못 간다 — AI 저장소 장애 하나로 서비스 전체 배포가 막힌다. AI 는
#    없어도 서비스가 도는(규칙 폴백) 부품이므로, 실패를 기록하고 넘어간다.
AI_READY=1
sync_repo "$AI_DIR" "$AI_REPO" "$AI_BRANCH" || {
  echo "  ⚠️  AI 저장소 동기화 실패 — AI 없이 계속한다(Spring 이 규칙 first-fit 으로 폴백)"
  AI_READY=0
}

# ── 4. .env 확인 ─────────────────────────────────────────────────────────────
log "4/6 .env 확인"

# 시드와 사전검사가 같은 목록을 본다 — 둘이 갈라지면 "안내는 여섯인데 검사는 넷" 이 된다.
REQUIRED_KEYS=(DB_HOST DB_PASSWORD JWT_SECRET APP_BASE_URL API_BASE_URL
               GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET MAIL_USERNAME MAIL_PASSWORD)

if [ ! -f "$APP_DIR/.env" ]; then
  # 🔴 .env.example 을 그대로 쓰면 안 된다. 거기엔 로컬 개발 기본값이 채워져 있어
  #    (DB_HOST=localhost · JWT_SECRET=replace-with-your-local-jwt-secret)
  #    아래 사전검사가 "비어 있는가" 만 보는 한 전부 통과한다. 특히 JWT_SECRET 은
  #    34바이트라 HS256 하한(32)을 넘겨 **서버가 정상 기동한다** — 공개 저장소에 박힌
  #    고정 시크릿으로 운영 토큰을 서명하는 상태가 조용히 생긴다. 기동 실패보다 나쁘다.
  #    그래서 필수값은 **비운 채로** 시드한다. 비어 있으면 사전검사가 반드시 잡는다.
  cp "$APP_DIR/.env.example" "$APP_DIR/.env"
  for k in "${REQUIRED_KEYS[@]}"; do
    sed -i "s|^${k}=.*|${k}=|" "$APP_DIR/.env"
  done
  cat <<'MSG'

  🔴 .env 를 만들었습니다. 값이 비어 있어 지금 기동하면 실패합니다.

     nano ~/openplan/.env

  아래 아홉을 채워야 뜹니다(4단계 사전검사가 같은 목록을 봅니다):
     DB_HOST         RDS 엔드포인트
     DB_PASSWORD     RDS 마스터 비밀번호
     JWT_SECRET      openssl rand -base64 48
     APP_BASE_URL    브라우저가 여는 프론트 주소   https://<도메인>
     API_BASE_URL    이 서버 주소                  https://<도메인>
     GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET   구글 클라우드 콘솔 발급값
     MAIL_USERNAME   Gmail 주소
     MAIL_PASSWORD   Gmail 앱 비밀번호 16자 (계정 비밀번호 아님)

  MAIL_* 이 없으면 가입 메일이 안 나가고, 이메일 미인증 계정은 로그인이
  403 으로 막혀 "떴지만 아무도 못 쓰는" 서버가 됩니다.

  GOOGLE_* 이 없으면 소셜 로그인 버튼이 눌리기는 하는데 항상
  /login?error=E-AUTH-010 으로 되돌아옵니다 — 서버는 멀쩡히 뜹니다.

  🔴 API_BASE_URL 에 구글 콘솔 등록값과 한 글자라도 다른 값을 넣으면 콜백이
     redirect_uri_mismatch 로 막힙니다. 구글은 HTTPS 만 받고 raw IP 는 등록조차
     안 되므로, 도메인 없이는 http://<공인 IP> 를 넣어도 소셜 로그인이 안 섭니다.
     카카오·네이버 키는 필수가 아닙니다(포기 순서에 있는 값이라 배포를 막지 않습니다).

  🔴 .env.example 의 예시값을 그대로 두면 미입력으로 봅니다. 특히 JWT_SECRET 은
     저장소에 박힌 값이라 그대로 쓰면 토큰을 누구나 위조할 수 있습니다.

  채운 뒤 이 스크립트를 다시 실행하십시오.

MSG
  exit 1
fi

# 필수값이 비어 있으면 기동 전에 멈춘다 — 뜬 뒤 500을 보는 것보다 낫다.
#
# 🔴 MAIL_* 가 목록에 든 이유: 인증 실구현(#22·#23)이 올라간 뒤로는 가입 메일이
#    나가지 않으면 로그인 자체가 불가능하다. AuthService 가 이메일 미인증 계정을
#    403 E-AUTH-005 로 막기 때문에, 메일 없이 뜬 서버는 "떴지만 아무도 못 쓰는"
#    상태가 된다. 그건 기동 실패보다 알아채기 어렵다.
#    MAIL_PASSWORD 는 구글 계정 비밀번호가 아니라 앱 비밀번호(16자)다.
#    MAIL_FROM 은 넣지 않는다 — MailConfig.resolveFrom 이 비어 있으면 SMTP 계정으로
#    대체하므로 선택값이다. 여기에 넣으면 안 채워도 되는 값 때문에 배포가 막힌다.
#
# 🔴 GOOGLE_* · API_BASE_URL 이 목록에 든 이유(2026-08-23 실측): 배포 서버의
#    /auth/oauth/{google,naver,kakao} 가 셋 다 302 /login?error=E-AUTH-010 이었다.
#    OAuthProperties.configured() 는 client-id/secret 이 비면 그 제공자를 막는데,
#    .env.example 의 GOOGLE_CLIENT_ID= 가 빈 값이라 "비어 있는가" 검사를 그대로
#    통과했다. 서버는 정상 기동하고 소셜 로그인만 죽어 있다 — MAIL_* 과 같은 종류의
#    "떴지만 못 쓰는" 상태다.
#    API_BASE_URL 은 redirect_uri 가 붙는 자리인데 목록에 없어 example 의
#    http://localhost:8080 이 살아남았다. 키를 채워도 콜백이 localhost 로 나간다.
#    🔴 카카오·네이버 키는 넣지 않는다 — 구글 최우선·둘은 포기 순서(6주차 문서
#    "인증 범위 결정")라, 필수로 걸면 놓기로 한 것 때문에 배포가 막힌다.
#
# 🔴 "비어 있는가" 만으로는 부족하다. .env.example 의 값을 그대로 둔 것도 미입력으로 본다 —
#    블록리스트를 쓰지 않고 example 과 대조하므로, 앞으로 예시 기본값이 늘어도 저절로 잡힌다.
missing=()
for k in "${REQUIRED_KEYS[@]}"; do
  v="$(grep -E "^${k}=" "$APP_DIR/.env" | cut -d= -f2- || true)"
  example="$(grep -E "^${k}=" "$APP_DIR/.env.example" | cut -d= -f2- || true)"
  if [ -z "$v" ] || [[ "$v" == *"<EC2_PUBLIC_IP>"* ]] \
     || { [ -n "$example" ] && [ "$v" = "$example" ]; }; then
    missing+=("$k")
  fi
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "🔴 .env 미입력: ${missing[*]}" >&2
  exit 1
fi

# ── 5. FE 빌드 ───────────────────────────────────────────────────────────────
log "5/6 프론트 빌드 (node:22-alpine 컨테이너)"
cd "$FE_DIR"
# API 베이스는 openapi 정본과 같은 /api/v1. 단일 오리진이라 상대경로면 충분하다.
printf 'VITE_API_BASE_URL=/api/v1\n' > .env.production
# 호스트 node 를 쓰지 않는다(1단계 주석 참조). 컨테이너가 root 로 돌아 dist 소유자가
# root 가 되지만 읽기 권한은 열려 있어 nginx 마운트에 지장 없다.
$DOCKER run --rm -v "$PWD":/app -w /app node:22-alpine \
  sh -c "npm ci --no-audit --no-fund && npm run build"

# 🔴 web/ 디렉터리 자체를 지우면 안 된다(2026-08-17 실측). nginx 는 이 경로를 바인드
#    마운트로 물고 있는데, 그 마운트는 컨테이너가 뜰 때 inode 로 고정된다. 디렉터리를
#    지우고 새로 만들면 nginx 는 사라진 옛 inode 를 계속 보게 되고, 컨테이너 안에서는
#    빈 디렉터리가 된다 — 재배포 직후 화면이 "/ → 403, /login → 404" 로 죽는다.
#    (compose 가 backend 만 재생성하고 nginx 는 그대로 두므로 저절로 낫지 않는다.)
#    그래서 디렉터리는 남기고 안의 내용만 갈아 끼운다.
sudo mkdir -p "$APP_DIR/web"
sudo find "$APP_DIR/web" -mindepth 1 -delete
sudo cp -r dist/. "$APP_DIR/web"/

# ── 6. 기동 ──────────────────────────────────────────────────────────────────
log "6/6 컨테이너 빌드·기동"
cd "$APP_DIR"

# 🔴 `up -d --build` 를 통째로 걸면 안 된다. 한 서비스의 빌드가 실패하면 up 이 통째로
#    실패해 **아무 컨테이너도 뜨지 않는다**(backend·nginx 포함). depends_on 의
#    required:false 는 헬스체크 대기만 우회할 뿐 빌드 실패는 막지 못한다.
#    AI 는 없어도 서비스가 도는 부품이므로 따로 빌드하고, 실패하면 빼고 올린다.
if [ "$AI_READY" = 1 ]; then
  if ! $DOCKER compose -f docker-compose.prod.yml build openplan-ai; then
    log "⚠️ AI 이미지 빌드 실패 — AI 없이 계속한다(Spring 이 규칙 first-fit 으로 폴백)"
    AI_READY=0
  fi
fi

if [ "$AI_READY" = 1 ]; then
  $DOCKER compose -f docker-compose.prod.yml up -d --build
else
  # --no-deps 로 AI 의존을 명시적으로 끊는다. 이 경로에서도 화면과 API 는 정상이고,
  # AI 초안만 규칙 first-fit 으로 대체된다.
  $DOCKER compose -f docker-compose.prod.yml up -d --build --no-deps backend nginx
fi

log "상태"
$DOCKER compose -f docker-compose.prod.yml ps

IP="$(curl -s --max-time 5 https://checkip.amazonaws.com || echo '<서버IP>')"
cat <<MSG

배포 완료. 확인할 것:

  화면      http://$IP/
  공개 API  http://$IP/api/v1/landing      ← 인증 없이 200 이어야 정상
  새로고침  http://$IP/dashboard           ← 404 가 나면 SPA 폴백 문제

로그:
  sudo docker compose -f docker-compose.prod.yml logs -f backend

MSG
