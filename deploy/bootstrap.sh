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
log "5/6 프론트 빌드 (node:22-alpine 컨테이너)"
cd "$FE_DIR"
# API 베이스는 openapi 정본과 같은 /api/v1. 단일 오리진이라 상대경로면 충분하다.
printf 'VITE_API_BASE_URL=/api/v1\n' > .env.production
# 호스트 node 를 쓰지 않는다(1단계 주석 참조). 컨테이너가 root 로 돌아 dist 소유자가
# root 가 되지만 읽기 권한은 열려 있어 nginx 마운트에 지장 없다.
$DOCKER run --rm -v "$PWD":/app -w /app node:22-alpine \
  sh -c "npm ci --no-audit --no-fund && npm run build"

sudo rm -rf "$APP_DIR/web"
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
