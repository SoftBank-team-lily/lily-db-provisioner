#!/usr/bin/env bash
# Terraform 을 Docker 로 실행 (로컬 설치 불필요). 자격증명은 ~/.aws 를 읽는다
#   ./tf.sh init | plan | apply | destroy | output ...
set -euo pipefail
cd "$(dirname "$0")"

HERE=$(pwd -W 2>/dev/null || pwd)
AWS_DIR=$(cygpath -w "$HOME/.aws" 2>/dev/null || echo "$HOME/.aws")

# provider 바이너리(.terraform)는 Docker 볼륨에 둔다.
# OneDrive 등 느린 바인드 마운트에 두면 "timeout while waiting for plugin to start" 가 난다
TTY=$([ -t 0 ] && echo "-it" || echo "")
MSYS_NO_PATHCONV=1 exec docker run --rm $TTY \
  -v "$HERE:/infra" -w /infra \
  -v lily-tfdata:/tfdata -e TF_DATA_DIR=/tfdata \
  -v "$AWS_DIR:/root/.aws:ro" \
  -e AWS_PROFILE="${AWS_PROFILE:-default}" \
  hashicorp/terraform:1.9 "$@"
