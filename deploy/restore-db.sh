#!/usr/bin/env bash
# OpenPlan — 운영 DB 복원 (빈 postgres 컨테이너에 pg_dump -Fc 덤프를 넣는다)
#
# 사용:
#   cd ~/openplan && bash deploy/restore-db.sh ~/openplan.dump
#   보통은 bootstrap.sh 가 RESTORE_DUMP=<경로> 를 받았을 때 6단계에서 부른다.
#
# 🔴 backend 가 뜨기 전에 돌려야 한다. backend 가 먼저 뜨면 Flyway 가 빈 DB 에 스키마를
#    만들고, 그 위로는 덤프가 "이미 있다" 오류로 들어가지 않는다. 그래서 테이블이 하나라도
#    있으면 복원을 거부한다 — 덮어쓰기는 운영 데이터를 지우는 일이라 이 스크립트가 하지 않는다.
#
# 🔴 --no-acl: RDS 시절 덤프에는 팀원 개인 DB 계정(jinsohee)에 준 GRANT 가 들어 있다. 이 DB 는
#    밖으로 안 열리고 그 롤도 없으므로 권한은 가져오지 않는다. 소유자는 덤프 그대로 openplan 이다
#    (컨테이너의 POSTGRES_USER 가 openplan 이라 롤이 이미 있다).

set -euo pipefail

DUMP="${1:-}"
APP_DIR="${APP_DIR:-$HOME/openplan}"
COMPOSE="sudo docker compose -f docker-compose.prod.yml"

log() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

[ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "🔴 덤프 파일이 없다: '${DUMP}'" >&2; exit 1; }
cd "$APP_DIR"
DB_USER="$(grep -E '^DB_USERNAME=' .env | cut -d= -f2- || true)"; DB_USER="${DB_USER:-openplan}"
DB_NAME="$(grep -E '^DB_NAME=' .env | cut -d= -f2- || true)";     DB_NAME="${DB_NAME:-openplan}"

log "1/4 postgres 기동"
$COMPOSE up -d postgres
for _ in $(seq 1 30); do
  $COMPOSE exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 && break
  sleep 2
done
$COMPOSE exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME"

log "2/4 비어 있는지 확인"
tables="$($COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select count(*) from information_schema.tables where table_schema='public'")"
if [ "$tables" != "0" ]; then
  echo "🔴 public 스키마에 테이블이 ${tables}개 있다 — 복원하지 않는다." >&2
  echo "   이미 복원했거나 backend 가 먼저 떠서 Flyway 가 스키마를 만든 것이다." >&2
  exit 1
fi

log "3/4 복원"
# 덤프를 컨테이너에 복사하지 않고 표준입력으로 흘린다 — 볼륨에 사본이 남지 않는다.
$COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" \
  --exit-on-error --no-acl < "$DUMP"

log "4/4 확인"
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select count(*) || ' tables' from information_schema.tables where table_schema='public'"
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select 'flyway ' || max(version) from flyway_schema_history where success"
echo "  🟢 복원 완료. 백업 폴더의 rds/rowcounts-rds.txt 와 행 수를 대조할 것."
