#!/usr/bin/env bash
# lily-tunnel 계정이 에이전트 인증서마다 정해진 역방향 포트 하나만 열게 한다 (lily-server 에서 sudo 로 실행, 여러 번 실행해도 된다).
#   sudo ./lily-tunnel-reverse.sh <RDS 엔드포인트> <lily-server 사설 IP> [포트 시작] [포트 끝]
#
# 온프레미스 DB(사용자 PC 의 DB, 사용자가 준 DB)를 클라우드 대기 Pod 에 연다.
#   에이전트:  ssh -R {사설 IP}:{포트}:{DB}  →  클라우드 Pod 가 {사설 IP}:{포트} 로 붙는다
#
# 권한은 인증서 key ID 로 정한다 (lily-builder TunnelCertificates 가 CA 로 서명한 값이라 에이전트가 바꿀 수 없다).
#   agent-{key}          RDS:5432 로의 -L 만
#   agent-{key}-p{port}  위에 더해 {사설 IP}:{port} 하나에만 -R
# 다른 에이전트의 포트를 열 수 없다. 그 포트를 열면 그 에이전트의 클라우드 Pod 접속(계정·비밀번호)을 받게 되기 때문이다.
#
# 하는 일
#   1. authorized_keys 의 cert-authority 줄을 TrustedUserCAKeys 로 옮긴다
#      (cert-authority 줄은 permitlisten 이 없어서 GatewayPorts 를 켜면 아무 포트나 열 수 있게 된다)
#   2. AuthorizedPrincipalsCommand 가 key ID 를 보고 옵션을 정한다
#   3. lily-tunnel 에만 GatewayPorts clientspecified (사설 IP 에 bind)
#   4. 일반 키 줄(팀 테스트 키)에는 -R 을 막는 permitlisten 을 붙인다
# 보안그룹: lily-server 에 포트 범위를 VPC 에서만 연다 (이 스크립트는 하지 않는다)
set -euo pipefail
RDS="$1"; REVERSE_HOST="$2"; FROM="${3:-20000}"; TO="${4:-20999}"
USER_NAME=lily-tunnel
KEYS="/home/$USER_NAME/.ssh/authorized_keys"
[[ "$RDS" =~ ^[a-z0-9.-]+$ ]] || { echo "잘못된 RDS 주소"; exit 1; }
[[ "$REVERSE_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "잘못된 사설 IP"; exit 1; }
[[ "$FROM" =~ ^[0-9]+$ && "$TO" =~ ^[0-9]+$ && "$FROM" -le "$TO" ]] || { echo "잘못된 포트 범위"; exit 1; }

BACKUP="$KEYS.$(date +%Y%m%d%H%M%S).bak"
cp -p "$KEYS" "$BACKUP"

# 1. CA 공개키
if grep -q '^cert-authority' "$KEYS"; then
  grep '^cert-authority' "$KEYS" | grep -oE 'ssh-ed25519 [A-Za-z0-9+/=]+' | head -1 > /etc/ssh/lily-agent-ca.pub
fi
[ -s /etc/ssh/lily-agent-ca.pub ] || { echo "CA 공개키가 없다 (authorized_keys 의 cert-authority 줄)"; exit 1; }
chmod 644 /etc/ssh/lily-agent-ca.pub

# 2. key ID → 옵션
cat > /etc/lily-tunnel.env <<EOF
RDS_HOST=$RDS
REVERSE_HOST=$REVERSE_HOST
PORT_FROM=$FROM
PORT_TO=$TO
EOF
chmod 644 /etc/lily-tunnel.env
cat > /usr/local/bin/lily-tunnel-principals <<'EOF'
#!/bin/sh
# sshd AuthorizedPrincipalsCommand. $1 = 인증서 key ID. 출력: "<옵션> lily-tunnel"
. /etc/lily-tunnel.env
base="restrict,port-forwarding,permitopen=\"$RDS_HOST:5432\",command=\"/bin/false\""
id="$1"
if echo "$id" | grep -Eq '^agent-[0-9a-f]{12}-p[0-9]{1,5}$'; then
  port="${id##*-p}"
  if [ "$port" -ge "$PORT_FROM" ] && [ "$port" -le "$PORT_TO" ]; then
    echo "$base,permitlisten=\"$REVERSE_HOST:$port\" lily-tunnel"
    exit 0
  fi
fi
if echo "$id" | grep -Eq '^agent-[0-9a-f]{12}(-p[0-9]{1,5})?$'; then
  # -R 없음 (permitlisten 이 없으면 아무 포트나 열 수 있다)
  echo "$base,permitlisten=\"127.0.0.1:1\" lily-tunnel"
fi
EOF
chmod 755 /usr/local/bin/lily-tunnel-principals

# 3. sshd
cat > /etc/ssh/sshd_config.d/50-lily-tunnel.conf <<EOF
Match User $USER_NAME
    TrustedUserCAKeys /etc/ssh/lily-agent-ca.pub
    AuthorizedPrincipalsCommand /usr/local/bin/lily-tunnel-principals %i
    AuthorizedPrincipalsCommandUser nobody
    GatewayPorts clientspecified
    AllowTcpForwarding yes
EOF

# 4. authorized_keys: cert-authority 줄을 빼고, 일반 키는 -R 을 막는다
TMP=$(mktemp)
grep -v '^cert-authority' "$KEYS" | sed -E '/permitlisten=/! s/^restrict,/restrict,permitlisten="127.0.0.1:1",/' > "$TMP" || true
install -m 600 -o "$USER_NAME" -g "$USER_NAME" "$TMP" "$KEYS"; rm -f "$TMP"

if ! sshd -t; then
  # 설정이 틀리면 되돌린다 (다음 재시작에 sshd 가 뜨지 않으면 접속이 끊긴다)
  rm -f /etc/ssh/sshd_config.d/50-lily-tunnel.conf
  install -m 600 -o "$USER_NAME" -g "$USER_NAME" "$BACKUP" "$KEYS"
  echo "sshd 설정 검사 실패, 되돌림"; exit 1
fi
systemctl reload ssh
echo "ok: $USER_NAME -L $RDS:5432, -R $REVERSE_HOST:$FROM-$TO (key ID 당 하나)"
