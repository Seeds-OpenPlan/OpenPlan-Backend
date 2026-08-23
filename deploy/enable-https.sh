#!/usr/bin/env bash
# OpenPlan — HTTPS 전환 (Let's Encrypt)
#
# 하는 일: 인증서 발급 → nginx 를 HTTPS 설정으로 교체 → 앱 주소를 https 로 전환.
#
# 사용:
#   cd ~/openplan && bash deploy/enable-https.sh
#
# 🔴 선행 조건 셋. 하나라도 빠지면 발급이 실패한다 — 아래에서 미리 검사한다.
#   1) DNS A 레코드가 이 서버를 가리킬 것 (전파에 10~30분)
#   2) EC2 보안그룹에 443 인바운드가 열려 있을 것
#   3) 80 이 열려 있을 것 — http-01 인증이 80 으로 온다. HTTPS 뒤에도 닫으면 안 된다

set -euo pipefail

DOMAIN="${DOMAIN:-openplan.services}"
WWW="www.${DOMAIN}"
EMAIL="${LETSENCRYPT_EMAIL:-}"
APP_DIR="${APP_DIR:-$HOME/openplan}"
COMPOSE="docker compose -f docker-compose.prod.yml"

cd "$APP_DIR"
log() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
DOCKER="sudo docker"
COMPOSE="sudo $COMPOSE"

if [ -z "$EMAIL" ]; then
  echo "🔴 LETSENCRYPT_EMAIL 이 필요합니다 — 만료 경고가 이 주소로 옵니다." >&2
  echo "   예: LETSENCRYPT_EMAIL=you@example.com bash deploy/enable-https.sh" >&2
  exit 1
fi

# ── 1. 선행 조건 검사 ────────────────────────────────────────────────────────
log "1/4 선행 조건 검사"

MYIP="$(curl -s --max-time 5 https://checkip.amazonaws.com || true)"
DNSIP="$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)"
if [ -z "$DNSIP" ]; then
  echo "🔴 $DOMAIN 의 A 레코드가 아직 안 보입니다. DNS 전파를 기다리십시오(10~30분)." >&2
  exit 1
fi
if [ -n "$MYIP" ] && [ "$DNSIP" != "$MYIP" ]; then
  # 막지는 않는다 — CDN·프록시를 앞에 두는 구성도 있다. 다만 대개는 오타다.
  echo "⚠️  DNS($DNSIP) 와 이 서버($MYIP) 가 다릅니다. 그대로 진행하면 발급이 실패할 수 있습니다."
fi
echo "  DNS: $DOMAIN → $DNSIP"

# ── 2. 갱신 통로 준비 ────────────────────────────────────────────────────────
log "2/4 갱신 통로 준비"
mkdir -p certbot/conf certbot/www
# 80 전용 설정으로 nginx 를 띄워 둔다 — 인증서가 없는 동안 HTTPS 설정을 올리면 기동이 깨진다.
$COMPOSE up -d nginx
sleep 2

# ── 3. 발급 ─────────────────────────────────────────────────────────────────
log "3/4 인증서 발급"
# webroot 방식. standalone 을 쓰면 80 을 잠깐 점유하려 nginx 를 내려야 하고, 그 사이 서비스가 끊긴다.
$DOCKER run --rm \
  -v "$APP_DIR/certbot/conf:/etc/letsencrypt" \
  -v "$APP_DIR/certbot/www:/var/www/certbot" \
  certbot/certbot certonly --webroot -w /var/www/certbot \
  -d "$DOMAIN" -d "$WWW" \
  --email "$EMAIL" --agree-tos --no-eff-email --non-interactive

if [ ! -f "certbot/conf/live/$DOMAIN/fullchain.pem" ]; then
  echo "🔴 발급에 실패했습니다. 위 로그를 보십시오 — 대개 DNS 미전파이거나 80 이 막힌 경우입니다." >&2
  exit 1
fi

# ── 4. 전환 ─────────────────────────────────────────────────────────────────
log "4/4 HTTPS 로 전환"
# nginx 설정 교체 (compose 가 .env 의 NGINX_CONF 를 본다)
if grep -q '^NGINX_CONF=' .env; then
  sed -i 's|^NGINX_CONF=.*|NGINX_CONF=./nginx-https.conf|' .env
else
  printf '\n# HTTPS 전환 (deploy/enable-https.sh, %s)\nNGINX_CONF=./nginx-https.conf\n' "$(date -u +%F)" >> .env
fi

# 앱이 만드는 링크(메일·OAuth 리다이렉트)도 https 여야 한다. http 로 남으면 구글이 교환을 거부하고,
# 메일 링크는 리다이렉트되며 쿠키가 붙지 않는다.
sed -i "s|^APP_BASE_URL=.*|APP_BASE_URL=https://$DOMAIN|" .env
sed -i "s|^API_BASE_URL=.*|API_BASE_URL=https://$DOMAIN|" .env

# 🔴 쿠키 Secure. HTTPS 로 넘어간 뒤에도 false 로 두면 인증 쿠키가 평문으로도 전송된다.
if grep -q '^COOKIE_SECURE=' .env; then
  sed -i 's|^COOKIE_SECURE=.*|COOKIE_SECURE=true|' .env
else
  printf 'COOKIE_SECURE=true\n' >> .env
fi

$COMPOSE up -d --force-recreate nginx backend
sleep 5

log "확인"
$COMPOSE ps
echo
curl -s -o /dev/null -w "  https://$DOMAIN/            → %{http_code}\n" --max-time 10 "https://$DOMAIN/" || true
curl -s -o /dev/null -w "  https://$DOMAIN/api/v1/landing → %{http_code}\n" --max-time 10 "https://$DOMAIN/api/v1/landing" || true
curl -s -o /dev/null -w "  http → https 리다이렉트     → %{http_code}\n" --max-time 10 "http://$DOMAIN/" || true

cat <<MSG

HTTPS 전환 완료.

🔴 갱신은 자동이 아닙니다. 인증서 수명은 90일입니다. cron 에 아래를 넣으십시오:

  0 3 * * * cd $APP_DIR && sudo docker run --rm \\
    -v $APP_DIR/certbot/conf:/etc/letsencrypt \\
    -v $APP_DIR/certbot/www:/var/www/certbot \\
    certbot/certbot renew --quiet && \\
    sudo docker compose -f docker-compose.prod.yml exec nginx nginx -s reload

🔴 구글 콘솔의 승인된 리디렉션 URI 를 https 주소로 바꿔야 소셜 로그인·구글 캘린더가 됩니다.

MSG
