# lily-db-provisioner

프로젝트별 DB 자동 생성 모듈 · SoftBank Hackathon 2026 · Team Lily

사용자가 프로젝트를 배포하면, 미리 띄워둔 엔진별 공용 RDS 인스턴스 안에 **그 프로젝트 전용 database + 계정**을 만들고, 앱에 주입할 접속 정보를 돌려준다.

```
프로젝트 등록   POST   /api/databases            공용 RDS 에 DB + 계정 생성 (1초 이내)
배포 직전       GET    /api/databases/{id}/env   DB_URL / DB_USERNAME / DB_PASSWORD 반환 → 컨테이너 env 로 주입
프로젝트 삭제   DELETE /api/databases/{id}       DB, 계정, 비밀번호, 메타데이터 삭제
```

| 구분 | 저장소 |
|---|---|
| 사용자 앱 DB | 공용 RDS (PostgreSQL / MySQL), 프로젝트마다 database + 계정 |
| 플랫폼 메타데이터 | DynamoDB `lily-managed-databases` |
| 테넌트 비밀번호 | SSM Parameter Store (SecureString) |

---

## 1. 현재 상태

### 구현 완료
- REST API: 엔진 목록, DB 생성·조회·목록·삭제, 접속 정보(env) 조회
- 엔진: PostgreSQL, MySQL (환경변수로 각각 on/off)
- 테넌트 격리, 계정당 커넥션 제한, 기본 DB 잠금 (PostgreSQL)
- 메타데이터 DynamoDB 저장, 프로젝트당 DB 1개 보장 (동시 요청 포함)
- 비밀번호 SSM 저장 (로컬은 메모리)
- 내부 API 토큰 인증, 헬스체크 (`dynamodb`, `engines`)
- Terraform: 공용 RDS, DynamoDB 테이블(`lily-managed-databases`, `lily-builds`), IAM 정책(프로비저너, lily-builder), lily-server 인스턴스 역할, 예산 알림 (`infra/`)
- 실제 AWS 검증 스크립트 (`infra/smoke-test.sh`)

### 실환경 통합 검증 (2026-09-30)
k3s(lily-server + worker 2) 에 세 모듈을 올리고 `lily-builder → lily-cicd → db-provisioner → RDS` 흐름을 확인했다.
- lily-builder 에 `lily-blog-sample` + `database=postgres` 요청 → Kaniko 빌드 → ECR push → lily-cicd 배포
- lily-cicd 가 프로비저너로 DB 생성·env 주입 → 앱이 RDS 테넌트 DB 에 붙어 Flyway 적용 → `http://blog.43.200.152.53.nip.io` 접속, 글 작성이 RDS 에 저장
- 재배포(blue → green) 시 같은 DB 재사용, 데이터 유지
- 처음 보는 appName(`blog2`)도 ECR 저장소 자동 생성부터 접속까지 한 번에 성공
- 클러스터 공용 설정(ECR 인증, ingress-nginx, lily-server 역할)은 [deploy/k3s/cluster/README.md](deploy/k3s/cluster/README.md)

### 권한 구조
AWS 권한은 lily-server 에만 있고, 사용자 코드(앱·빌드)는 AWS 자격증명이 없는 worker 에서만 돈다. 플랫폼 API 는 사용자 앱에서 호출할 수 없다. 노드·IAM·RBAC·네트워크·비밀값별 상세는 [docs/permissions.md](docs/permissions.md).

### 온프레미스 · 클라우드 버스팅 DB 공유
온프레미스 앱은 SSH 터널(lily-server 의 포워딩 전용 계정 `lily-tunnel`, [db-tunnel-user.sh](deploy/k3s/cluster/db-tunnel-user.sh))로 같은 RDS 에 붙는다. 접속 정보는 lily-builder `/api/burst/apps/{app}/database` 가 이 모듈의 `/env?host=&port=` 로 받는다. 같은 projectId 라 클라우드 배포와 같은 DB 다.

반대 방향(사용자 PC 의 DB, 사용자가 준 DB 를 클라우드 대기 Pod 에 여는 것)은 에이전트가 `ssh -R {lily-server 사설 IP}:{포트}:{DB}` 로 연다. 이 경우 lily-cicd 는 이 모듈을 부르지 않고 lily-builder 가 만든 `databaseEnv` 를 쓴다. lily-server 설정은 [lily-tunnel-reverse.sh](deploy/k3s/cluster/lily-tunnel-reverse.sh) (`sudo ./lily-tunnel-reverse.sh <RDS> <사설 IP> [20000] [20999]`, 여러 번 실행해도 된다).
- authorized_keys 의 `cert-authority` 줄을 `TrustedUserCAKeys` 로 옮기고, `AuthorizedPrincipalsCommand` 가 인증서 key ID 로 권한을 정한다
  - `agent-{key}`: RDS:5432 로의 `-L` 만
  - `agent-{key}-p{port}`: 위에 더해 `{사설 IP}:{port}` 하나에만 `-R` (`permitlisten`). 다른 에이전트의 포트를 열면 그 대기 Pod 의 DB 접속을 받게 되므로 포트는 에이전트마다 하나다
- `GatewayPorts clientspecified` 는 `lily-tunnel` 에만 켠다. 일반 키 줄에는 `-R` 을 막는 `permitlisten` 을 붙인다
- 실행 전 authorized_keys 에 `cert-authority` 줄이 있어야 한다 (없으면 중단). 포트 범위 보안그룹(VPC 내부만)은 따로 연다
- `db-tunnel-user.sh` 를 다시 실행하면 authorized_keys 를 덮어쓰므로 `lily-tunnel-reverse.sh` 도 다시 실행한다

### 아직 안 된 것
- **MySQL 실환경 검증**: 로컬 Docker(MySQL 8.4) 에서만 확인. RDS MySQL 은 `enable_mysql = true` 로 띄워서 검증 필요

---

## 2. 보장하는 동작

| 항목 | 동작 |
|---|---|
| 격리 | 테넌트 계정은 자기 DB 에만 접속·접근 가능. 다른 테넌트 DB 는 PostgreSQL 은 접속 단계에서, MySQL 은 쿼리 단계에서 거부 |
| 기본 DB 잠금 | PostgreSQL 기본 DB(`postgres`, `template1`) 접속 불가 → 다른 프로젝트 DB 이름 목록 노출 차단. 프로비저너가 기동 시 자동 적용 |
| 자원 제한 | 테넌트 계정당 동시 커넥션 20 (`DB_CONNECTION_LIMIT`) |
| 유일성 | 프로젝트당 DB 1개. 같은 projectId 로 동시에 요청해도 1개만 생성, 나머지는 409 |
| 원자성 | 생성 중 실패하면 만들다 만 DB/계정을 지우고 `FAILED` 로 기록. 같은 projectId 로 다시 POST 하면 정리 후 재생성 |
| 멱등 삭제 | 삭제는 여러 번 호출해도 안전 (`IF EXISTS`). 앱이 접속 중이어도 세션을 끊고 삭제 (PostgreSQL) |
| 빈 DB | 새로 만든 DB 는 비어 있다. 스키마는 앱이 기동하면서 직접 마이그레이션 |
| 비밀값 | 비밀번호는 `/env` 응답에만 나온다. 메타데이터·로그·에러 메시지에는 남지 않는다 |
| 이름 | DB 이름 = 계정 이름 = `p_` + 16자리 hex. 서버가 생성하며 사용자 입력은 SQL 에 들어가지 않는다 |

---

## 3. 다른 모듈 개발자 참고

### 공통
- 모든 `/api/**` 요청에 `Authorization: Bearer {PROVISIONER_API_TOKEN}` 헤더 필요. `/actuator/**` 는 토큰 없이 접근
- 에러 형식은 `lily-blog-sample` 과 같다: `{"timestamp", "code", "message"}`
- `projectId` 는 lily-cicd 의 `appName` 과 같은 값. 소문자·숫자·하이픈(`-`), 처음과 끝은 영숫자, 최대 55자

### CI/CD
→ 전체 규칙: [docs/cicd-integration.md](docs/cicd-integration.md)

lily-cicd 의 `HttpDatabaseProvisioner` 가 배포할 때마다 아래를 수행한다 (projectId = appName).

1. `GET /api/databases?projectId={appName}` 로 기존 DB 조회
2. 없거나 `FAILED` 면 `POST /api/databases` 로 생성
3. `GET /api/databases/{id}/env` → `env` 를 그대로 새 슬롯 컨테이너 환경변수로 주입
4. (프로젝트 삭제 기능이 생기면) `DELETE /api/databases/{id}`

- 스키마 마이그레이션은 **앱이 기동하면서** 한다 (Flyway, Prisma 등). 파이프라인에서 따로 돌릴 필요 없음
- `/env` 응답에는 비밀번호가 있으므로 파이프라인 로그에 출력하지 않는다

### 사용자 앱 (배포 대상)
| 변수 | 예시 | 용도 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://<host>:5432/p_1b62...` | Spring 등 JDBC (`lily-blog-sample` 규칙) |
| `DB_USERNAME` / `DB_PASSWORD` | `p_1b62...` / (랜덤 24자) | |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `DB_*` 와 같은 값 | 일반 Spring Boot 앱 (코드 수정 없이 자동으로 읽음) |
| `DATABASE_URL` | `postgresql://user:pw@<host>:5432/p_1b62...` | Node, Python 등 |

- 계정당 동시 연결은 20 (`DB_CONNECTION_LIMIT`). 넘으면 접속 거부
- 그래서 `/env` 에 커넥션 풀 크기 3 을 같이 내려준다 (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, `DB_POOL_SIZE`, 설정: `APP_DB_POOL_SIZE`). lily-cicd canary 전환 중 Pod 6개가 떠도 6 x 3 = 18
- RDS PostgreSQL 은 SSL 을 강제할 수 있다. JDBC 기본값(`sslmode=prefer`)이면 그대로 동작

### 로깅 · 모니터링
- 운영(`prod`) 로그는 JSON 한 줄. `app`, `version`, `color` 필드 포함 (`lily-blog-sample` 과 같은 형식)
- 메트릭: `GET /actuator/prometheus`
- 헬스: `GET /actuator/health` → `dynamodb`(메타데이터 테이블), `engines`(엔진별 RDS 관리자 접속)
- 주요 로그: `database created`, `database deleted`, `database create failed`, `database delete failed`

### 인프라
- `infra/` 의 Terraform 이 공용 RDS, DynamoDB 테이블 2개, IAM 정책 2개, lily-server 역할을 만든다 → [infra/README.md](infra/README.md)
- lily-server 역할(`server-role.tf`)에 `{name}-db-provisioner` 정책(DynamoDB 테이블 + SSM `/lily/db/*`)이 붙는다. 다른 역할에 붙이려면 `provisioner_role_name`
- 보안그룹: 앱 서버와 프로비저너 → RDS 5432/3306

---

## 4. API

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/engines` | 사용 가능한 엔진. 예: `["postgres","mysql"]` |
| POST | `/api/databases` | 생성. body `{"projectId":"blog","engine":"postgres"}` → `201` |
| GET | `/api/databases?projectId=blog` | 목록 (projectId 생략 시 전체) |
| GET | `/api/databases/{id}` | 상태 조회 (비밀번호 없음) |
| GET | `/api/databases/{id}/env?host=&port=` | 앱에 주입할 환경변수 (비밀번호 포함). `host`/`port` 를 주면 접속 주소만 바꾼다 (온프레미스 터널) |
| DELETE | `/api/databases/{id}` | 삭제 → `204` |

`POST /api/databases` 응답
```json
{
  "id": "1b626675-e4a1-4703-85e8-d2c750aa226a",
  "projectId": "blog",
  "engine": "postgres",
  "status": "AVAILABLE",
  "dbName": "p_1b626675e4a14703",
  "host": "lily-shared-postgres.xxxx.ap-northeast-2.rds.amazonaws.com",
  "port": 5432,
  "errorMessage": null,
  "createdAt": "2026-09-29T13:44:04.960Z",
  "updatedAt": "2026-09-29T13:44:04.968Z"
}
```

`GET /api/databases/{id}/env` 응답
```json
{
  "databaseId": "1b626675-e4a1-4703-85e8-d2c750aa226a",
  "env": {
    "DB_URL": "jdbc:postgresql://lily-shared-postgres.xxxx.rds.amazonaws.com:5432/p_1b626675e4a14703",
    "DB_USERNAME": "p_1b626675e4a14703",
    "DB_PASSWORD": "********",
    "SPRING_DATASOURCE_URL": "jdbc:postgresql://lily-shared-postgres.xxxx.rds.amazonaws.com:5432/p_1b626675e4a14703",
    "SPRING_DATASOURCE_USERNAME": "p_1b626675e4a14703",
    "SPRING_DATASOURCE_PASSWORD": "********",
    "DATABASE_URL": "postgresql://p_1b626675e4a14703:********@lily-shared-postgres.xxxx.rds.amazonaws.com:5432/p_1b626675e4a14703",
    "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "3",
    "DB_POOL_SIZE": "3"
  }
}
```

### 상태
| status | 의미 |
|---|---|
| `AVAILABLE` | 사용 가능 |
| `FAILED` | 생성 또는 삭제 실패. `errorMessage` 확인 |
| `CREATING` / `DELETING` | 진행 중 (보통 1초 이내) |

### 에러
| HTTP | code | 상황 |
|---|---|---|
| 400 | `BAD_REQUEST` | 요청 형식 오류, 지원하지 않는 engine, `/env` 의 `host`/`port` 중 하나만 있거나 형식 오류 |
| 400 | `ENGINE_NOT_ENABLED` | 이 프로비저너에서 꺼진 엔진 |
| 401 | `UNAUTHORIZED` | 토큰 없음 / 틀림 |
| 404 | `NOT_FOUND` | 없는 id |
| 409 | `ALREADY_EXISTS` | 프로젝트에 이미 DB 가 있음 |
| 409 | `NOT_READY` | `AVAILABLE` 이 아닌데 `/env` 요청 |
| 502 | `PROVISIONING_FAILED` | RDS 쪽 실패 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 오류. 원인은 로그에만 남김 |

---

## 5. 내부 설계

### 테넌트 생성 · 삭제 SQL
| | PostgreSQL (13+) | MySQL (8.0+) |
|---|---|---|
| 생성 | `CREATE ROLE ... LOGIN CONNECTION LIMIT 20` → `GRANT role TO CURRENT_USER` → `CREATE DATABASE ... OWNER role` → `REVOKE ALL ON DATABASE ... FROM PUBLIC` | `CREATE DATABASE` → `CREATE USER ... WITH MAX_USER_CONNECTIONS 20` → `GRANT ALL ON db.*` |
| 삭제 | `DROP DATABASE ... WITH (FORCE)` → `DROP ROLE` | `DROP USER` → `DROP DATABASE` |
| 기동 시 1회 | 관리자가 소유한 `postgres`, `template1` 에서 `REVOKE CONNECT, TEMPORARY ... FROM PUBLIC` | 없음 |

- `GRANT role TO CURRENT_USER`: RDS 마스터는 superuser 가 아니라서, 만든 role 의 멤버여야 그 role 을 OWNER 로 DB 를 만들고 지울 수 있다 (PG16 부터 필수)
- 기본 DB 잠금은 관리자가 **소유한** DB 에만 적용한다. 소유자는 권한이 유지되므로 관리자 자신은 막히지 않는다
- DDL 실패 시 스프링 예외 메시지(실행한 SQL = 비밀번호 포함)는 버리고 DB 가 돌려준 원인 메시지만 남긴다

### 메타데이터 (DynamoDB)
파티션 키 `pk`(String) 하나, 아이템 두 종류.

| pk | 내용 |
|---|---|
| `DB#{databaseId}` | projectId, engine, dbName, dbUser, host, port, secretRef, status, errorMessage, createdAt, updatedAt |
| `PROJECT#{projectId}` | databaseId (가드 아이템) |

- 생성: 두 아이템을 `TransactWriteItems` 로 쓰고 둘 다 `attribute_not_exists(pk)` 조건 → 프로젝트당 1개 보장
- projectId 조회: 가드 → DB 아이템 순으로 `ConsistentRead`. GSI(eventually consistent)를 쓰지 않아 생성 직후 조회도 안전
- 목록: Scan (해커톤 규모. 커지면 GSI)
- 비밀번호는 저장하지 않고 `secretRef`(SSM 경로)만 저장

### 비밀번호
- SSM `/lily/db/{databaseId}/password` (SecureString, 기본 KMS 키)
- 로컬(`SECRET_STORE=memory`)은 프로세스 메모리. 재시작하면 사라진다

---

## 6. 설정 (환경변수)

| 이름 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` (이미지는 `prod`) | `local`: DynamoDB Local + 테이블 자동 생성, 사람이 읽는 로그 / `prod`: AWS, JSON 로그 |
| `PROVISIONER_API_TOKEN` | (없음) | 운영 필수. 비어 있으면 인증 꺼짐 (`prod` 는 기동 실패) |
| `AWS_REGION` | `ap-northeast-2` | 자격증명은 AWS 기본 체인 (운영은 인스턴스 역할) |
| `DYNAMODB_TABLE` | `lily-managed-databases` | |
| `DYNAMODB_ENDPOINT` | (없음, `local` 은 `http://localhost:8000`) | 비우면 AWS |
| `DYNAMODB_CREATE_TABLE` | `false` (`local` 은 `true`) | 로컬 전용 |
| `SECRET_STORE` | `memory` | `memory` / `ssm` |
| `SSM_PREFIX` | `/lily/db` | |
| `DB_CONNECTION_LIMIT` | `20` | 테넌트 계정당 최대 커넥션 |
| `APP_DB_POOL_SIZE` | `3` | `/env` 로 내려주는 앱 커넥션 풀 크기 |
| `PG_ENABLED` | `false` | |
| `PG_ADMIN_URL` | `jdbc:postgresql://localhost:5432/postgres` | RDS 마스터 계정 접속 |
| `PG_ADMIN_USERNAME` / `PG_ADMIN_PASSWORD` | `postgres` / `postgres` | |
| `PG_PUBLIC_HOST` / `PG_PUBLIC_PORT` | `localhost` / `5432` | 앱에 알려줄 주소 (RDS 엔드포인트) |
| `MYSQL_ENABLED` | `false` | |
| `MYSQL_ADMIN_URL` | `jdbc:mysql://localhost:3306/` | |
| `MYSQL_ADMIN_USERNAME` / `MYSQL_ADMIN_PASSWORD` | `root` / `root` | |
| `MYSQL_PUBLIC_HOST` / `MYSQL_PUBLIC_PORT` | `localhost` / `3306` | |
| `SERVER_PORT` | `8080` | |
| `APP_VERSION` / `APP_COLOR` | `dev` / `blue` | 로그의 `version`, `color` 필드 |

`infra/` 에서 `terraform apply` 하면 AWS 용 값이 채워진 `infra/.env.aws` 가 생성된다.

---

## 7. 실행 · 테스트

### 로컬 (Docker 만 필요)
RDS 대신 Postgres / MySQL 컨테이너, DynamoDB 대신 DynamoDB Local 을 쓴다.
```bash
docker compose up -d --build
curl -H "Authorization: Bearer local-token" localhost:8080/api/engines
curl -H "Authorization: Bearer local-token" -H "Content-Type: application/json" \
     -X POST localhost:8080/api/databases -d '{"projectId":"blog","engine":"postgres"}'
docker compose down -v
```

### 자동 테스트
DynamoDB Local 이 필요하다. RDS 쪽은 가짜 프로비저너로 대체한다.
```bash
docker compose up -d dynamodb
gradle test
```
JDK 없이 Docker 로 돌릴 때는 소스를 컨테이너 안으로 복사해서 빌드한다. OneDrive 등 동기화 폴더를 바로 마운트하면 파일 I/O 가 매우 느리다.
```bash
docker run --rm --network db-provisioner_default -e DYNAMODB_ENDPOINT=http://dynamodb:8000 \
  -v "$PWD:/src:ro" gradle:8.12-jdk21 sh -c \
  'mkdir /work && cp -r /src/settings.gradle /src/build.gradle /src/src /work && cd /work && gradle test --no-daemon'
```

### 실제 AWS
```bash
cd infra
./tf.sh apply        # 공용 RDS + DynamoDB 생성, .env.aws 생성
./smoke-test.sh      # 프로비저너를 prod 로 띄워 전체 검증
./tf.sh destroy      # 정리
```
자세한 순서는 [infra/README.md](infra/README.md).

### k3s
`deploy/k3s/db-provisioner.yaml` 로 lily-server 노드(`lily-system`)에 올린다. 설정은 Secret `db-provisioner-env` (`infra/.env.aws` 로 생성). 순서는 [deploy/k3s/README.md](deploy/k3s/README.md).

---

## 8. 검증 결과

### 자동 테스트 (15개, DynamoDB Local)
생성·조회·삭제, 중복 409, 동시 요청 8개 중 1개만 성공, 꺼진 엔진 400, 입력 검증 400, 토큰 401, 인코딩된 경로로 토큰 우회 불가, 헬스체크는 토큰 없이, 생성 실패 시 롤백 후 재시도, 삭제 시 DB·가드 아이템 모두 제거, 다른 DB 를 가리키는 가드는 남김, 엔진 목록, 엔진별 env 형식

### 로컬 Docker (PostgreSQL 16 / MySQL 8.4 컨테이너)
- 다른 테넌트 DB 접근 거부 (두 엔진), 틀린 비밀번호 거부, 커넥션 제한 20 적용
- `lily-blog-sample` 을 `/env` 결과로 배포 → Flyway V1·V2 적용, CRUD 정상
- 앱이 접속 중인 상태에서 삭제 → DB·계정 제거
- 로그에 비밀번호·SQL 없음

### 실제 AWS (2026-09-30, RDS PostgreSQL 16 `db.t4g.micro` 서울, `smoke-test.sh` 15/15)
- RDS 마스터 계정(비 superuser)으로 테넌트 생성·삭제 성공
- 비밀번호 SSM SecureString 저장·삭제
- 다른 테넌트 DB / 기본 `postgres` DB 접속 거부, 틀린 비밀번호 거부
- `lily-blog-sample` 배포 → Flyway 시드 조회
- 삭제 후 DB·계정·SSM·DynamoDB 모두 정리

---

## 9. 제약 · 주의사항

- **리소스 공유**: CPU·메모리·IO 는 테넌트끼리 공유한다. 커넥션 제한 외에 무거운 쿼리는 막지 못한다. 부하가 큰 프로젝트는 전용 인스턴스로 분리하는 것이 확장 방향
- **스토리지 쿼터 없음**: PostgreSQL·MySQL 모두 DB 별 용량 제한 기능이 없다. DB 별 사용량 모니터링으로 대응
- **장애 범위**: 인스턴스 장애는 모든 테넌트에 영향. 운영 전환 시 Multi-AZ 검토
- **메타데이터 노출 (PostgreSQL)**: 테넌트는 자기 DB 안에서 `pg_database` 카탈로그로 다른 DB 이름을 조회할 수 있다 (PostgreSQL 구조상 제거 불가). 데이터는 볼 수 없다
- **Flyway baseline**: `lily-blog-sample` 처럼 `baseline-on-migrate: true` 인 앱은 테이블이 이미 있는 DB 에 붙으면 V1 을 건너뛴다. 프로비저너가 주는 DB 는 비어 있어 문제없지만, 기존 DB 를 연결할 때 주의
- **로컬 AWS 자격증명**: Docker 의 aws-cli 로 `configure` 하면 `~/.aws` 파일이 root 전용(0600)이 된다. 로컬에서 프로비저너 컨테이너를 AWS 에 붙일 때는 root 로 실행해야 한다 (`smoke-test.sh` 에 반영됨). 운영은 인스턴스 역할이라 무관

---

## 기술 스택

Java 21 · Spring Boot 3.4 · AWS SDK v2 (DynamoDB, SSM) · Spring JDBC + HikariCP · PostgreSQL / MySQL JDBC · Actuator + Micrometer Prometheus · Logstash Logback Encoder · Terraform (AWS provider 5.x)
