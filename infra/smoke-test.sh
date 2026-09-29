#!/usr/bin/env bash
# 실제 AWS(RDS + DynamoDB + SSM) 에 프로비저너를 붙여서 한 바퀴 검증한다.
# 전제: ./tf.sh apply 완료 (.env.aws 생성됨), ~/.aws 자격증명
#
# 확인 항목
#   1. 헬스체크 (dynamodb, engines)
#   2. 프로젝트 DB 2개 생성 → RDS 마스터 권한으로 CREATE ROLE / GRANT / CREATE DATABASE 가 되는지
#   3. SSM 에 비밀번호 저장됐는지
#   4. 테넌트 계정으로 자기 DB 접속 + 테이블 생성
#   5. 다른 테넌트 DB 접속 거부 / 틀린 비밀번호 거부
#   6. (lily-blog-sample 이미지가 있으면) 샘플 앱 배포
#   7. 삭제 → DROP DATABASE WITH (FORCE) / DROP ROLE, SSM·DynamoDB 정리 확인
set -uo pipefail
cd "$(dirname "$0")"

ENV_FILE=$(pwd -W 2>/dev/null || pwd)/.env.aws
AWS_DIR=$(cygpath -w "$HOME/.aws" 2>/dev/null || echo "$HOME/.aws")
[ -f .env.aws ] || { echo ".env.aws 없음: 먼저 ./tf.sh apply"; exit 1; }
grep -q '^PG_ENABLED=true' .env.aws || { echo "postgres 가 꺼져 있음 (enable_postgres=true 필요)"; exit 1; }

TOKEN=$(grep '^PROVISIONER_API_TOKEN=' .env.aws | cut -d= -f2)
REGION=$(grep '^AWS_REGION=' .env.aws | cut -d= -f2)
PG_HOST=$(grep '^PG_PUBLIC_HOST=' .env.aws | cut -d= -f2)
API=http://localhost:18081
PASS=0; FAIL=0
ok()   { echo "  [OK]   $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
api()  { curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }
json() { python -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
psql_as() {  # user password db sql
  MSYS_NO_PATHCONV=1 docker run --rm -e PGPASSWORD="$2" -e PGSSLMODE=require postgres:16 \
    psql -h "$PG_HOST" -U "$1" -d "$3" -tAc "$4" 2>&1 | tail -1
}
aws_cli() {
  MSYS_NO_PATHCONV=1 docker run --rm -v "$AWS_DIR:/root/.aws:ro" -e AWS_PROFILE="${AWS_PROFILE:-default}" \
    -e AWS_REGION="$REGION" amazon/aws-cli "$@"
}
cleanup() { docker rm -f lily-provisioner-aws lily-blog-aws >/dev/null 2>&1; }
trap cleanup EXIT

echo "## 0. 프로비저너 이미지 빌드 + 실행 (prod 프로파일, 실제 AWS)"
docker build -q -t db-provisioner .. >/dev/null || { echo "build 실패"; exit 1; }
cleanup
MSYS_NO_PATHCONV=1 docker run -d --name lily-provisioner-aws -p 18081:8080 \
  --env-file "$ENV_FILE" -e AWS_PROFILE="${AWS_PROFILE:-default}" \
  -v "$AWS_DIR:/aws:ro" -e AWS_SHARED_CREDENTIALS_FILE=/aws/credentials -e AWS_CONFIG_FILE=/aws/config \
  --user root `# aws-cli 컨테이너가 만든 ~/.aws 파일은 root 전용(0600). 운영은 인스턴스 역할이라 무관` \
  db-provisioner >/dev/null
for i in $(seq 1 40); do curl -s $API/actuator/health/readiness | grep -q UP && break; sleep 3; done

echo "## 1. 헬스체크"
HEALTH=$(curl -s $API/actuator/health)
echo "$HEALTH" | json "d['components']['dynamodb']['status']" | grep -q UP && ok "dynamodb UP" || fail "dynamodb: $HEALTH"
echo "$HEALTH" | json "d['components']['engines']['status']" | grep -q UP && ok "engines UP (RDS 관리자 접속)" || fail "engines: $HEALTH"

echo "## 2. 프로젝트 DB 생성 (RDS 마스터 권한 검증)"
A=$(api -X POST $API/api/databases -d '{"projectId":"smoke-a","engine":"postgres"}')
B=$(api -X POST $API/api/databases -d '{"projectId":"smoke-b","engine":"postgres"}')
A_ID=$(echo "$A" | json "d.get('id','')"); B_ID=$(echo "$B" | json "d.get('id','')")
[ "$(echo "$A" | json "d.get('status')")" = AVAILABLE ] && ok "smoke-a AVAILABLE" || fail "smoke-a: $A"
[ "$(echo "$B" | json "d.get('status')")" = AVAILABLE ] && ok "smoke-b AVAILABLE" || fail "smoke-b: $B"

A_ENV=$(api $API/api/databases/$A_ID/env)
A_USER=$(echo "$A_ENV" | json "d['env']['DB_USERNAME']"); A_PW=$(echo "$A_ENV" | json "d['env']['DB_PASSWORD']")
B_USER=$(echo "$B" | json "d['dbName']")

echo "## 3. SSM 비밀번호 저장"
aws_cli ssm get-parameter --name "/lily/db/$A_ID/password" --query Parameter.Type --output text 2>&1 \
  | grep -q SecureString && ok "SSM SecureString 저장됨" || fail "SSM 파라미터 없음"

echo "## 4. 테넌트 계정으로 자기 DB 사용"
R=$(psql_as "$A_USER" "$A_PW" "$A_USER" "CREATE TABLE t(id int); INSERT INTO t VALUES (1); SELECT 'rows='||count(*) FROM t;")
[ "$R" = "rows=1" ] && ok "자기 DB 에 테이블 생성/조회" || fail "자기 DB 사용 실패: $R"

echo "## 5. 격리"
R=$(psql_as "$A_USER" "$A_PW" "$B_USER" "SELECT 1")
echo "$R" | grep -q "CONNECT privilege" && ok "다른 테넌트 DB 접속 거부" || fail "격리 실패: $R"
R=$(psql_as "$A_USER" "wrong-password" "$A_USER" "SELECT 1")
echo "$R" | grep -q "password authentication failed" && ok "틀린 비밀번호 거부" || fail "비밀번호 검사 실패: $R"

echo "## 6. 샘플 앱 배포 (빈 DB 인 smoke-b 사용. 샘플 앱은 baseline-on-migrate 라 테이블이 있으면 V1 을 건너뜀)"
if docker image inspect lily-blog-sample >/dev/null 2>&1; then
  api $API/api/databases/$B_ID/env | json "'\n'.join(f'{k}={v}' for k,v in d['env'].items())" > .blog.env
  MSYS_NO_PATHCONV=1 docker run -d --name lily-blog-aws -p 18082:8080 --env-file "$(pwd -W 2>/dev/null || pwd)/.blog.env" lily-blog-sample >/dev/null
  rm -f .blog.env
  for i in $(seq 1 40); do curl -s localhost:18082/actuator/health/readiness | grep -q UP && break; sleep 3; done
  N=$(curl -s localhost:18082/api/posts | json "len(d)" 2>/dev/null)
  [ "$N" = "2" ] && ok "lily-blog-sample 기동 + Flyway 시드 2건 조회" || fail "샘플 앱: posts=$N"
  docker rm -f lily-blog-aws >/dev/null
else
  echo "  (skip) lily-blog-sample 이미지 없음"
fi

echo "## 7. 삭제"
for id in $A_ID $B_ID; do
  C=$(api -o /dev/null -w '%{http_code}' -X DELETE $API/api/databases/$id)
  [ "$C" = 204 ] && ok "DELETE $id" || fail "DELETE $id -> $C"
done
R=$(psql_as "$A_USER" "$A_PW" "$A_USER" "SELECT 1")
echo "$R" | grep -qE "does not exist|authentication failed" && ok "DB/계정 삭제됨" || fail "삭제 후에도 접속됨: $R"
aws_cli ssm get-parameter --name "/lily/db/$A_ID/password" >/dev/null 2>&1 && fail "SSM 파라미터 남아 있음" || ok "SSM 파라미터 삭제됨"
[ "$(api $API/api/databases)" = "[]" ] && ok "DynamoDB 메타데이터 비어 있음" || fail "메타데이터 남아 있음"

echo
echo "결과: OK $PASS / FAIL $FAIL"
[ "$FAIL" = 0 ] || { echo "--- provisioner logs (tail) ---"; docker logs --tail 30 lily-provisioner-aws; exit 1; }
