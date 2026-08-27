#!/usr/bin/env bash
# OpenPlan — CD 설치. 서버에서 한 번만 실행한다.
#
#   ssh -i ~/.ssh/openplan-key.pem ec2-user@openplan.services
#   cd ~/openplan && git fetch origin && git switch -C "$(cat cd.conf 2>/dev/null | grep -oP '(?<=^BE_BRANCH=).*' || echo main)" FETCH_HEAD
#   BE_BRANCH=main bash deploy/install-cd.sh
#
# 🔴 BE_BRANCH 를 반드시 넘겨야 한다. 안 넘기면 지금 서버가 무엇으로 도는지 알 수 없어
#    설치가 멈춘다 — 기본값으로 조용히 채우면 그 값이 실제 배포분과 다를 때 첫 회차가
#    서버를 다른 브랜치로 되돌린다. 그게 이 파일이 존재하는 이유다.
#
# 보통은 손으로 부를 일이 없다. bootstrap.sh 7단계가 배포 끝에 알아서 부른다.

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
     짐작해서 채우면 그 값이 실제 배포분과 다를 때, 5분 뒤 CD 가 서버를
     그 브랜치로 되돌립니다 — 기동 실패가 아니라 조용한 교체라
     화면으로는 안 보입니다.

     확인:  git -C ~/openplan rev-parse --abbrev-ref HEAD
     실행:  BE_BRANCH=<그 브랜치> bash deploy/install-cd.sh

MSG
    exit 1
  fi
  cp "$APP_DIR/deploy/cd.conf.example" "$CONF"
  sed -i "s|^BE_BRANCH=.*|BE_BRANCH=${BE_BRANCH}|" "$CONF"
  [ -n "${FE_BRANCH:-}" ] && sed -i "s|^FE_BRANCH=.*|FE_BRANCH=${FE_BRANCH}|" "$CONF"
  # 🔴 AI 도 배포 대상이다(#59 이후). 여기서 안 넣으면 cd-watch.sh 가 기본값으로
  #    떨어지는데, 그 기본값이 실제 배포한 브랜치와 다르면 추적과 배포가 어긋난다.
  if [ -n "${AI_BRANCH:-}" ]; then
    if grep -qE '^AI_BRANCH=' "$CONF"; then
      sed -i "s|^AI_BRANCH=.*|AI_BRANCH=${AI_BRANCH}|" "$CONF"
    else
      printf 'AI_BRANCH=%s\n' "${AI_BRANCH}" >> "$CONF"
    fi
  fi
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
  # 🔴 AI 디렉터리는 bootstrap.sh 와 같은 식으로 구한다($HOME 고정이 아니다).
  #    APP_DIR 을 옮긴 서버에서 둘이 갈라지면 기준선이 영원히 안 맞아 5분마다
  #    전체 재빌드가 돈다 — 형식뿐 아니라 "어디를 보는가"도 한 벌이어야 한다.
  ai="$(git -C "$(dirname "$APP_DIR")/openplan-ai" rev-parse HEAD 2>/dev/null || echo unknown)"
  # 🔴 필드 구성과 순서는 cd-watch.sh 의 WANT 와 정확히 같아야 한다. 한쪽만 바뀌면
  #    매 회차 "바뀐 것으로 보여" 무한 재배포가 된다. 둘을 같은 PR 에서만 고칠 것.
  printf '%s@%s %s@%s %s@%s' "$BE_BRANCH" "$be" "$FE_BRANCH" "$fe" "${AI_BRANCH:-main}" "$ai" > "$STATE"
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
  배포 대상 변경 nano ~/openplan/cd.conf

  배포된 것 확인 curl -s https://openplan.services/deploy-status.json
MSG
