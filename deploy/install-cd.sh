#!/usr/bin/env bash
# OpenPlan — CD 설치. 서버에서 한 번만 실행한다.
#
#   ssh -i ~/.ssh/openplan-key.pem ec2-user@openplan.services
#   cd ~/openplan && git fetch origin && git switch -C "$(cat cd.conf 2>/dev/null | grep -oP '(?<=^BE_BRANCH=).*' || echo main)" FETCH_HEAD
#   BE_BRANCH=deploy/w6-integration bash deploy/install-cd.sh
#
# 🔴 BE_BRANCH 를 반드시 넘겨야 한다. 안 넘기면 지금 서버가 무엇으로 도는지 알 수 없어
#    설치가 멈춘다 — 기본값(main)으로 조용히 채우면 첫 회차에 백엔드가 91커밋 뒤로
#    돌아가고 AI 와 가용시간 저장이 사라진다. 그게 이 파일이 존재하는 이유다.

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/openplan}"
CONF="$APP_DIR/cd.conf"
UNIT_DIR=/etc/systemd/system

log() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

[ -d "$APP_DIR/deploy" ] || { echo "🔴 $APP_DIR/deploy 가 없다. 저장소가 클론된 곳에서 실행할 것" >&2; exit 1; }

# ── 1. cd.conf ──────────────────────────────────────────────────────────────
log "1/4 cd.conf"
if [ -f "$CONF" ]; then
  echo "  이미 있다 — 건드리지 않는다. 현재 값:"
  grep -E '^(BE|FE|AI)_BRANCH=' "$CONF" | sed 's/^/    /'
else
  if [ -z "${BE_BRANCH:-}" ]; then
    cat >&2 <<'MSG'

  🔴 BE_BRANCH 를 넘기지 않았습니다.

     지금 서버가 어떤 브랜치로 돌고 있는지는 이 스크립트가 알 수 없습니다.
     기본값(main)으로 채우면 첫 회차에 백엔드가 되돌아갑니다 —
     main 에는 AiPlacementAdapter 도 PreferencesController 도 없어
     AI 가 규칙 폴백으로 내려앉고 가용시간 저장이 다시 깨집니다.
     둘 다 화면으로는 안 보입니다.

     확인:  git -C ~/openplan rev-parse --abbrev-ref HEAD
     실행:  BE_BRANCH=<그 브랜치> bash deploy/install-cd.sh

MSG
    exit 1
  fi
  cp "$APP_DIR/deploy/cd.conf.example" "$CONF"
  sed -i "s|^BE_BRANCH=.*|BE_BRANCH=${BE_BRANCH}|" "$CONF"
  [ -n "${FE_BRANCH:-}" ] && sed -i "s|^FE_BRANCH=.*|FE_BRANCH=${FE_BRANCH}|" "$CONF"
  [ -n "${AI_BRANCH:-}" ] && sed -i "s|^AI_BRANCH=.*|AI_BRANCH=${AI_BRANCH}|" "$CONF"
  echo "  만들었다:"
  grep -E '^(BE|FE|AI)_BRANCH=' "$CONF" | sed 's/^/    /'
fi

# ── 2. 현재 배포분을 기준선으로 ─────────────────────────────────────────────
# 🔴 이걸 안 하면 설치 직후 첫 회차가 무조건 전체 재배포를 돈다. 지금은 그게
#    실제로 필요한 상태지만(FE 가 밀려 있다), 설치와 배포는 다른 결정이다.
#    기준선을 현재 클론의 SHA 로 잡아 두고, 배포는 사람이 판단해 시작하게 한다.
log "2/4 기준선"
STATE="$APP_DIR/.cd-state"
if [ -f "$STATE" ]; then
  echo "  이미 있다: $(cat "$STATE")"
else
  # shellcheck disable=SC1090
  . "$CONF"
  be="$(git -C "$APP_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
  fe="$(git -C "$HOME/openplan-fe" rev-parse HEAD 2>/dev/null || echo unknown)"
  ai="$(git -C "$HOME/openplan-ai" rev-parse HEAD 2>/dev/null || echo unknown)"
  printf '%s@%s %s@%s %s@%s' "$BE_BRANCH" "$be" "$FE_BRANCH" "$fe" "$AI_BRANCH" "$ai" > "$STATE"
  echo "  현재 배포분으로 잡았다: $(cat "$STATE")"
fi

# ── 3. systemd ──────────────────────────────────────────────────────────────
log "3/4 systemd 유닛"
sudo install -m 0644 "$APP_DIR/deploy/openplan-cd.service" "$UNIT_DIR/openplan-cd.service"
sudo install -m 0644 "$APP_DIR/deploy/openplan-cd.timer"   "$UNIT_DIR/openplan-cd.timer"
sudo systemctl daemon-reload
sudo systemctl enable --now openplan-cd.timer
systemctl list-timers openplan-cd.timer --no-pager | sed 's/^/  /'

# ── 4. 안내 ─────────────────────────────────────────────────────────────────
log "4/4 완료"
cat <<'MSG'
  상태 보기      systemctl list-timers openplan-cd.timer
  최근 로그      journalctl -u openplan-cd.service -n 80 --no-pager
  지금 한 번     sudo systemctl start openplan-cd.service
  강제 재배포    FORCE=1 bash ~/openplan/deploy/cd-watch.sh
  잠시 끄기      sudo systemctl stop openplan-cd.timer
  배포 대상 변경 nano ~/openplan/cd.conf     ← 리뷰가 끝나면 BE_BRANCH=main

  배포된 것 확인 curl -s https://openplan.services/deploy-status.json
MSG
