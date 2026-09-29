#!/usr/bin/env bash
# AWS CLI 를 Docker 로 실행. 자격증명은 ~/.aws 에 저장된다
#   ./aws.sh configure
#   ./aws.sh sts get-caller-identity
set -euo pipefail

AWS_DIR=$(cygpath -w "$HOME/.aws" 2>/dev/null || echo "$HOME/.aws")
mkdir -p "$HOME/.aws"

MSYS_NO_PATHCONV=1 exec docker run --rm -it \
  -v "$AWS_DIR:/root/.aws" \
  -e AWS_PROFILE="${AWS_PROFILE:-default}" \
  amazon/aws-cli "$@"
