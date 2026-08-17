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
# 🔴 이 스크립트는 아직 실제 EC2에서 실행된 적이 없다. 1차 배포가 검증이다.
#    실패하면 어느 단계에서 멈췄는지(아래 log 출력) 그대로 알려줄 것.
#
# 🔴 .env 는 이 스크립트가 만들지 않는다. 비밀값이 들어가므로 사람이 직접 채운다.

set -euo pipefail

BE_REPO="${BE_REPO:-https://github.com/Seeds-OpenPlan/OpenPlan-Backend.git}"
FE_REPO="${FE_REPO:-https://github.com/Seeds-OpenPlan/OpenPlan-Frontend.git}"
# 🔴 배포 파일(docker-compose.prod.yml·이 스크립트)이 아직 main 에 머지되지 않았다.
#    머지 전까지는 이 브랜치를 받아야 한다. 머지된 뒤 BE_BRANCH=main 으로 바꾼다.
BE_BRANCH="${BE_BRANCH:-feat/deploy-compose}"
FE_BRANCH="${FE_BRANCH:-main}"
APP_DIR="${APP_DIR:-$HOME/openplan}"
FE_DIR="$HOME/openplan-fe"

log() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

# ── 1. 패키지 ────────────────────────────────────────────────────────────────
log "1/6 패키지 설치 (docker · git · nodejs)"
sudo dnf -y update
sudo dnf -y install docker git nodejs

# ── 2. Docker ────────────────────────────────────────────────────────────────
log "2/6 Docker 기동 + 권한"
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

# compose v2 플러그인 — AL2023 기본 저장소에 없어 바이너리를 직접 놓는다.
if ! docker compose version >/dev/null 2>&1; then
  log "     compose 플러그인 설치"
  CLI_DIR=/usr/libexec/docker/cli-plugins
  sudo mkdir -p "$CLI_DIR"
  ARCH="$(uname -m)"
  sudo curl -fsSL \
    "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${ARCH}" \
    -o "$CLI_DIR/docker-compose"
  sudo chmod +x "$CLI_DIR/docker-compose"
fi

# usermod 는 다음 로그인부터 적용된다. 이번 세션은 sudo 로 docker 를 부른다.
DOCKER="sudo docker"

# ── 3. 저장소 ────────────────────────────────────────────────────────────────
log "3/6 저장소 클론/갱신"
if [ -d "$APP_DIR/.git" ]; then git -C "$APP_DIR" pull --ff-only; else git clone --depth 1 -b "$BE_BRANCH" "$BE_REPO" "$APP_DIR"; fi
if [ -d "$FE_DIR/.git" ];  then git -C "$FE_DIR"  pull --ff-only; else git clone --depth 1 -b "$FE_BRANCH" "$FE_REPO" "$FE_DIR";  fi

# ── 4. .env 확인 ─────────────────────────────────────────────────────────────
log "4/6 .env 확인"
if [ ! -f "$APP_DIR/.env" ]; then
  cp "$APP_DIR/.env.prod.example" "$APP_DIR/.env"
  cat <<'MSG'

  🔴 .env 를 만들었습니다. 값이 비어 있어 지금 기동하면 실패합니다.

     nano ~/openplan/.env

  최소한 이 넷은 채워야 뜹니다:
     DB_HOST         RDS 엔드포인트
     DB_PASSWORD     RDS 마스터 비밀번호
     JWT_SECRET      openssl rand -base64 48
     APP_BASE_URL / API_BASE_URL   http://<이 서버 공인 IP>

  채운 뒤 이 스크립트를 다시 실행하십시오.

MSG
  exit 1
fi

# 필수값이 비어 있으면 기동 전에 멈춘다 — 뜬 뒤 500을 보는 것보다 낫다.
missing=()
for k in DB_HOST DB_PASSWORD JWT_SECRET APP_BASE_URL; do
  v="$(grep -E "^${k}=" "$APP_DIR/.env" | cut -d= -f2- || true)"
  if [ -z "$v" ] || [[ "$v" == *"<EC2_PUBLIC_IP>"* ]]; then
    missing+=("$k")
  fi
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "🔴 .env 미입력: ${missing[*]}" >&2
  exit 1
fi

# ── 5. FE 빌드 ───────────────────────────────────────────────────────────────
log "5/6 프론트 빌드 (vite)"
cd "$FE_DIR"
# API 베이스는 openapi 정본과 같은 /api/v1. 단일 오리진이라 상대경로면 충분하다.
printf 'VITE_API_BASE_URL=/api/v1\n' > .env.production
npm ci
npm run build

rm -rf "$APP_DIR/web"
cp -r dist "$APP_DIR/web"

# ── 6. 기동 ──────────────────────────────────────────────────────────────────
log "6/6 컨테이너 빌드·기동"
cd "$APP_DIR"
$DOCKER compose -f docker-compose.prod.yml up -d --build

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
