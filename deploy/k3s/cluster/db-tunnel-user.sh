#!/usr/bin/env bash
# lily-server 에 온프레미스용 DB 터널 계정을 만든다 (lily-server 에서 sudo 로 1회 실행).
#   sudo ./db-tunnel-user.sh <RDS 엔드포인트> "<ssh-ed25519 공개키>"
#
# - 셸 없음, 이 키로는 RDS:5432 로의 포트포워딩만 된다 (다른 목적지, pty, 에이전트·X11 포워딩 금지)
# - 온프레미스 에이전트: DB_TUNNEL_SSH_HOST=<lily-server 공개 IP>, DB_TUNNEL_SSH_KEY=<개인키 경로>, DB_TUNNEL_REMOTE_HOST=<RDS>
# - RDS 보안그룹은 노드 보안그룹을 허용하므로 lily-server 에서 RDS 로 나가는 경로는 이미 열려 있다
set -euo pipefail
RDS="$1"; PUBKEY="$2"; USER_NAME=lily-tunnel
[[ "$RDS" =~ ^[a-z0-9.-]+$ ]] || { echo "잘못된 RDS 주소"; exit 1; }
[[ "$PUBKEY" =~ ^ssh-(ed25519|rsa)\  ]] || { echo "잘못된 공개키"; exit 1; }
id "$USER_NAME" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$USER_NAME"
install -d -m 700 -o "$USER_NAME" -g "$USER_NAME" "/home/$USER_NAME/.ssh"
echo "restrict,port-forwarding,permitopen=\"$RDS:5432\",command=\"/bin/false\" $PUBKEY" > "/home/$USER_NAME/.ssh/authorized_keys"
chown "$USER_NAME:$USER_NAME" "/home/$USER_NAME/.ssh/authorized_keys"; chmod 600 "/home/$USER_NAME/.ssh/authorized_keys"
echo "ok: $USER_NAME -> $RDS:5432 only"
