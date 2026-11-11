#!/usr/bin/env bash
# OpenPlan — 운영 DB 일일 백업 (cron 이 부른다. deploy/install-cron.sh 가 등록한다)
#
# 🔴 RDS 가 해 주던 자동 백업이 없어져서 생긴 파일이다. 같은 VM 의 디스크에 쌓이므로
#    "실수로 지운 데이터" 는 되살리지만 **VM 이나 디스크를 잃으면 함께 사라진다**.
#    그래서 가끔은 밖으로 가져가야 한다:
#      gcloud compute scp openplan-prod:~/openplan/backups/openplan-<날짜>.dump . --zone us-central1-f
#
# 형식은 AWS 종료 백업과 같은 pg_dump -Fc 다 — 복원은 deploy/restore-db.sh 로 한다.

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/openplan}"
KEEP_DAYS="${KEEP_DAYS:-14}"
OUT_DIR="$APP_DIR/backups"
cd "$APP_DIR"
DB_USER="$(grep -E '^DB_USERNAME=' .env | cut -d= -f2- || true)"; DB_USER="${DB_USER:-openplan}"
DB_NAME="$(grep -E '^DB_NAME=' .env | cut -d= -f2- || true)";     DB_NAME="${DB_NAME:-openplan}"

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"
out="$OUT_DIR/openplan-$(date +%Y%m%d-%H%M).dump"

# 🔴 임시 이름에 쓰고 성공해야 옮긴다. 실패한 반쪽 파일이 정상 백업처럼 남으면, 정작
#    필요할 때 복원이 깨진다. 실패하면 반쪽 파일은 지운다 — 아래 보관 정리는 .part 를
#    보지 않아서, 남겨 두면 실패할 때마다 디스크에 쌓인다.
trap 'rm -f "$out.part"' EXIT
sudo docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$out.part"
mv "$out.part" "$out"
chmod 600 "$out"

find "$OUT_DIR" -name 'openplan-*.dump' -mtime +"$KEEP_DAYS" -delete
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup ok $(basename "$out") $(stat -c %s "$out")B"
