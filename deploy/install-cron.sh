#!/usr/bin/env bash
# OpenPlan — 정기 작업 등록 (DB 백업 · 인증서 갱신). 여러 번 돌려도 안전하다.
#
# 보통은 손으로 부를 일이 없다. bootstrap.sh 9단계가 배포 끝에 알아서 부른다.
#
# 🔴 인증서 갱신은 enable-https.sh 가 "cron 에 넣으라" 고 안내만 하고 등록하지 않았다.
#    그래서 AWS 서버는 2026-08-23 발급분(cert1, 11-21 만료)을 한 번도 갱신하지 않았다.
#    안내는 빠진다 — 배포에 붙여 등록한다(CD 를 bootstrap 에 붙인 것과 같은 이유, D-92).

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/openplan}"
MARK="# openplan-managed"

BACKUP="30 4 * * * bash $APP_DIR/deploy/backup-db.sh >> $APP_DIR/backups/backup.log 2>&1 $MARK"
RENEW="0 3 * * * cd $APP_DIR && sudo docker run --rm -v $APP_DIR/certbot/conf:/etc/letsencrypt -v $APP_DIR/certbot/www:/var/www/certbot certbot/certbot renew --quiet && sudo docker compose -f docker-compose.prod.yml exec -T nginx nginx -s reload $MARK"

mkdir -p "$APP_DIR/backups"
# 이 스크립트가 넣은 줄만 갈아 끼운다 — 사람이 넣은 다른 줄은 건드리지 않는다.
{ crontab -l 2>/dev/null | grep -vF "$MARK" || true; echo "$BACKUP"; echo "$RENEW"; } | crontab -
crontab -l | grep -F "$MARK" | sed 's/^/  /'
