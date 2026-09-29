# db-provisioner

**프로젝트별 DB 자동 생성 모듈** · 소프트뱅크 해커톤 2026 · Team Lily

사용자가 프로젝트를 배포할 때, 미리 띄워둔 **공용 DB 인스턴스(RDS)** 안에 그 프로젝트 전용 DB와 계정을 만들어 준다.
CI/CD 는 여기서 받은 환경변수를 앱 컨테이너에 그대로 넣기만 하면 된다.

```
사용자 프로젝트 등록 ──▶ POST /api/databases ──▶ 공용 RDS 에 DB + 계정 생성 (1초 이내)
CI/CD 배포 직전      ──▶ GET  /api/databases/{id}/env ──▶ DB_URL, DB_USERNAME, DB_PASSWORD 주입
프로젝트 삭제        ──▶ DELETE /api/databases/{id} ──▶ DB, 계정, 비밀번호 삭제
```

## 이 모듈의 범위

| 하는 것 | 하지 않는 것 |
|---|---|
| 프로젝트 전용 DB + 계정 생성·삭제 | RDS 인스턴스 생성 (인프라 담당, 미리 띄워둠) |
| 비밀번호 생성 후 보관소(SSM)에 저장 | 앱 스키마 마이그레이션 (앱이 기동하면서 Flyway 등으로 직접 수행) |
| 앱에 주입할 환경변수 제공 | 환경변수를 컨테이너에 넣는 일 (CI/CD 담당) |
| 메타데이터를 플랫폼 DB(DynamoDB)에 저장 | DynamoDB 테이블 생성 (IaC 담당, 아래 정의 참고) |

## API

모든 `/api/**` 요청에는 `Authorization: Bearer {PROVISIONER_API_TOKEN}` 헤더가 필요하다.
(응답에 DB 비밀번호가 포함되기 때문. 헬스체크 `/actuator/**` 는 토큰 없이 접근)

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/engines` | 만들 수 있는 엔진 목록. 예: `["postgres","mysql"]` |
| POST | `/api/databases` | DB 생성. body: `{"projectId":"blog","engine":"postgres"}` |
| GET | `/api/databases?projectId=blog` | 목록 (projectId 생략 시 전체) |
| GET | `/api/databases/{id}` | 상태 조회 (비밀번호 없음) |
| GET | `/api/databases/{id}/env` | **앱에 주입할 환경변수** (비밀번호 포함) |
| DELETE | `/api/databases/{id}` | DB, 계정, 비밀번호 삭제 |
| GET | `/actuator/health` | `engines`: 엔진별 관리자 접속 상태, `dynamodb`: 메타데이터 테이블 상태 |

### 응답 예시

`POST /api/databases` → `201 Created`
```json
{
  "id": "1b626675-e4a1-4703-85e8-d2c750aa226a",
  "projectId": "blog",
  "engine": "postgres",
  "status": "AVAILABLE",
  "dbName": "p_1b626675e4a14703",
  "host": "lily-pg.xxxx.ap-northeast-2.rds.amazonaws.com",
  "port": 5432,
  "errorMessage": null,
  "createdAt": "2026-09-29T13:44:04.960Z",
  "updatedAt": "2026-09-29T13:44:04.968Z"
}
```

`GET /api/databases/{id}/env` → `200 OK`
```json
{
  "databaseId": "1b626675-e4a1-4703-85e8-d2c750aa226a",
  "env": {
    "DB_URL": "jdbc:postgresql://lily-pg.xxxx.rds.amazonaws.com:5432/p_1b626675e4a14703",
    "DB_USERNAME": "p_1b626675e4a14703",
    "DB_PASSWORD": "********",
    "DATABASE_URL": "postgresql://p_1b626675e4a14703:********@lily-pg.xxxx.rds.amazonaws.com:5432/p_1b626675e4a14703"
  }
}
```

- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 는 `lily-blog-sample` 의 연동 규칙과 같다 (Spring 앱은 그대로 동작)
- `DATABASE_URL` 은 Node, Python 등 Spring 이 아닌 앱용

### 상태값

| status | 의미 |
|---|---|
| `AVAILABLE` | 사용 가능. `/env` 조회 가능 |
| `FAILED` | 생성 또는 삭제 실패. `errorMessage` 확인. 같은 projectId 로 다시 POST 하면 정리 후 재생성 |
| `CREATING` / `DELETING` | 진행 중 (보통 1초 이내) |

### 에러 응답

`lily-blog-sample` 과 같은 형식: `{"timestamp": "...", "code": "...", "message": "..."}`

| HTTP | code | 상황 |
|---|---|---|
| 400 | `BAD_REQUEST` | 요청 형식 오류, 지원하지 않는 engine 값 |
| 400 | `ENGINE_NOT_ENABLED` | 이 프로비저너에서 켜지 않은 엔진 |
| 401 | `UNAUTHORIZED` | 토큰 없음/틀림 |
| 404 | `NOT_FOUND` | 없는 id |
| 409 | `ALREADY_EXISTS` | 프로젝트에 이미 DB 가 있음 (프로젝트당 1개) |
| 409 | `NOT_READY` | AVAILABLE 이 아닌데 `/env` 요청 |
| 502 | `PROVISIONING_FAILED` | RDS 쪽에서 실패 |

## 격리 · 보안

프로젝트마다 **DB 와 같은 이름의 전용 계정**을 만들고, 그 DB 에만 권한을 준다.

| | PostgreSQL | MySQL |
|---|---|---|
| 생성 | `CREATE ROLE` → `CREATE DATABASE ... OWNER` → `REVOKE ALL ON DATABASE ... FROM PUBLIC` | `CREATE DATABASE` → `CREATE USER` → `GRANT ALL ON db.*` |
| 연결 수 제한 | `CONNECTION LIMIT 20` | `MAX_USER_CONNECTIONS 20` |
| 삭제 | `DROP DATABASE ... WITH (FORCE)` (접속 중인 세션도 끊음) → `DROP ROLE` | `DROP USER` → `DROP DATABASE` |

- DB/계정 이름은 `p_` + 16자리 hex 로 **서버가 생성**한다. 사용자 입력은 SQL 에 들어가지 않는다
- 비밀번호는 24자리 랜덤(영숫자). 메타데이터에는 **보관소 경로만** 저장한다
- 에러 메시지·로그에 SQL(비밀번호 포함)이 남지 않도록 DB 가 돌려준 원인 메시지만 기록한다

## 메타데이터 (플랫폼 DB: DynamoDB)

테이블 하나, 파티션 키 `pk`(String) 하나. 아이템 두 종류를 둔다.

| pk | 내용 |
|---|---|
| `DB#{databaseId}` | id, projectId, engine, dbName, dbUser, host, port, secretRef, status, errorMessage, createdAt, updatedAt |
| `PROJECT#{projectId}` | databaseId (가드 아이템) |

- **프로젝트당 DB 1개**: 생성 시 두 아이템을 `TransactWriteItems` 로 같이 쓰고, 둘 다 `attribute_not_exists(pk)` 조건을 건다. 같은 프로젝트로 동시에 요청해도 하나만 성공하고 나머지는 409 (테스트로 확인)
- **projectId 조회**: GSI 는 eventually consistent 라서, 가드 아이템 → DB 아이템 순서로 `ConsistentRead` 로 읽는다. 생성 직후 조회도 안전
- **전체 목록**: Scan (해커톤 규모). 커지면 GSI 추가
- 비밀번호는 저장하지 않는다

운영 테이블 정의 (Terraform):
```hcl
resource "aws_dynamodb_table" "managed_databases" {
  name         = "lily-managed-databases"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}
```

필요한 IAM 권한 (프로비저너 실행 역할):
- DynamoDB: `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Scan`, `DescribeTable` (+ `TransactWriteItems` 는 위 권한으로 동작) → 테이블 ARN 한정
- SSM: `PutParameter`, `GetParameter`, `DeleteParameter` → `arn:aws:ssm:*:*:parameter/lily/db/*`, 기본 KMS 키 사용

## 설정 (환경변수)

| 이름 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` (이미지에서는 `prod`) | `local`: DynamoDB Local + 테이블 자동 생성 / `prod`: AWS + JSON 로그 |
| `PROVISIONER_API_TOKEN` | (없음) | **운영 필수.** 비어 있으면 인증 꺼짐 |
| `DYNAMODB_TABLE` | `lily-managed-databases` | 메타데이터 테이블 |
| `DYNAMODB_ENDPOINT` | (없음, `local` 은 `http://localhost:8000`) | 비우면 AWS |
| `DYNAMODB_CREATE_TABLE` | `false` (`local` 은 `true`) | 테이블이 없으면 생성. 로컬 전용 |
| `AWS_REGION` | `ap-northeast-2` | 자격증명은 EC2/ECS 인스턴스 역할 (기본 체인) |
| `DB_CONNECTION_LIMIT` | `20` | 프로젝트 계정 하나의 최대 커넥션 수 |
| `SECRET_STORE` | `memory` | `memory`: 프로세스 메모리 (개발용, 재시작 시 사라짐) / `ssm`: AWS SSM SecureString |
| `SSM_PREFIX` | `/lily/db` | SSM 경로. `{prefix}/{id}/password` |
| `PG_ENABLED` | `false` | PostgreSQL 사용 여부 |
| `PG_ADMIN_URL` | `jdbc:postgresql://localhost:5432/postgres` | 관리자 접속 (RDS 마스터 계정) |
| `PG_ADMIN_USERNAME` / `PG_ADMIN_PASSWORD` | `postgres` / `postgres` | |
| `PG_PUBLIC_HOST` / `PG_PUBLIC_PORT` | `localhost` / `5432` | 사용자 앱에 알려줄 주소 (보통 RDS 엔드포인트) |
| `MYSQL_ENABLED` | `false` | MySQL 사용 여부 |
| `MYSQL_ADMIN_URL` | `jdbc:mysql://localhost:3306/` | |
| `MYSQL_ADMIN_USERNAME` / `MYSQL_ADMIN_PASSWORD` | `root` / `root` | |
| `MYSQL_PUBLIC_HOST` / `MYSQL_PUBLIC_PORT` | `localhost` / `3306` | |

## RDS 준비 (인프라 담당과 함께, 1회)

- 엔진 버전: **PostgreSQL 13 이상**, **MySQL 8.0 이상**
- 프라이빗 서브넷에 두고 퍼블릭 접속은 끈다. 보안그룹은 앱 서버(EC2, 이후 EKS 노드)와 프로비저너에서 오는 5432/3306 만 허용
- PostgreSQL 은 기본 DB 에 모든 계정이 접속할 수 있어서, 다른 프로젝트 DB 의 **이름 목록**이 보인다 (데이터는 볼 수 없음). 아래로 막는다:
  ```sql
  REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
  REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
  ```

## 로컬 실행

필요한 것: Docker (JDK 21, Gradle 8.12 가 있으면 직접 실행도 가능)

```bash
# DynamoDB Local + Postgres + MySQL + 프로비저너를 한 번에 (RDS 대신 컨테이너를 공용 인스턴스로 사용)
docker compose up -d --build

# 확인 (토큰: local-token)
curl -H "Authorization: Bearer local-token" localhost:8080/api/engines
curl -H "Authorization: Bearer local-token" -H "Content-Type: application/json" \
     -X POST localhost:8080/api/databases -d '{"projectId":"blog","engine":"postgres"}'

# 정리
docker compose down -v
```

테스트는 **DynamoDB Local 이 필요**하다 (RDS 쪽은 가짜 프로비저너로 대체):
```bash
docker compose up -d dynamodb
gradle test                      # JDK 가 있으면

# JDK 없이 Docker 로
docker run --rm --network db-provisioner_default -e DYNAMODB_ENDPOINT=http://dynamodb:8000   -v "$PWD:/workspace" -w /workspace gradle:8.12-jdk21 gradle test --no-daemon
```

## 검증한 것

- API 테스트 10개 (DynamoDB Local): 생성/조회/삭제, 중복 409, **동시 요청 8개 중 1개만 성공**, 엔진 미사용 400, 입력 검증, 인증 401, 생성 실패 시 롤백 후 재시도, 삭제 시 DB·가드 아이템 모두 제거
- 실제 Postgres 16 / MySQL 8.4 에서:
  - 프로젝트 A 계정으로 B 의 DB 접속 → 거부 (두 엔진 모두)
  - 틀린 비밀번호 → 거부, 연결 수 제한 20 적용 확인
  - **`lily-blog-sample` 을 `/env` 결과로 배포 → Flyway V1, V2 자동 적용, CRUD 정상**
  - 앱이 접속 중인 상태에서 삭제 → DB, 계정 모두 제거
  - 로그에 비밀번호/SQL 노출 없음

### 실제 AWS 검증 (2026-09-30, `infra/smoke-test.sh`, 14/14 통과)

RDS PostgreSQL 16 (`db.t4g.micro`, 서울) + DynamoDB + SSM, 프로비저너 `prod` 프로파일:
- RDS 마스터 계정(비 superuser)으로 `CREATE ROLE` → `GRANT ... TO CURRENT_USER` → `CREATE DATABASE ... OWNER` 성공
- 비밀번호 SSM SecureString 저장 / 삭제
- 테넌트 격리 (다른 DB `CONNECT` 거부), 틀린 비밀번호 거부
- `lily-blog-sample` 배포 → Flyway 시드 조회
- `DROP DATABASE WITH (FORCE)` / `DROP ROLE` / DynamoDB 아이템 정리

인프라 생성과 검증 방법은 [infra/README.md](infra/README.md) 참고.

> 참고: `lily-blog-sample` 은 `baseline-on-migrate: true` 라서 **테이블이 이미 있는 DB** 에 붙으면 V1 을 건너뛰고 기준선만 찍는다.
> 프로비저너가 주는 DB 는 비어 있으므로 문제없지만, 기존 DB 를 연결(BYO)할 때는 주의.

## 기술 스택

Java 21 · Spring Boot 3.4 · AWS SDK v2 (DynamoDB, SSM) · Spring JDBC + HikariCP (공용 RDS DDL) · PostgreSQL / MySQL 드라이버 · Actuator + Micrometer Prometheus · Logstash Logback Encoder
