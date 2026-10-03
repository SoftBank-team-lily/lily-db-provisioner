#!/usr/bin/env bash
# Terraform 을 Docker 로 실행한다. gcloud 애플리케이션 기본 자격증명을 읽는다.
#   ./tf.sh init | plan | apply | destroy | output ...
set -euo pipefail
cd "$(dirname "$0")"

HERE=$(pwd -W 2>/dev/null || pwd)
GCLOUD_DIR=$(cygpath -w "$HOME/.config/gcloud" 2>/dev/null || echo "$HOME/.config/gcloud")

TTY=$([ -t 0 ] && echo "-it" || echo "")
MSYS_NO_PATHCONV=1 exec docker run --rm $TTY \
  -v "$HERE:/infra" -w /infra \
  -v lily-tfdata-gcp:/tfdata -e TF_DATA_DIR=/tfdata \
  -v "$GCLOUD_DIR:/root/.config/gcloud:ro" \
  hashicorp/terraform:1.9 "$@"
