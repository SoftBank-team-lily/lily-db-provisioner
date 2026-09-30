# CI/CD 연동 규칙

CI/CD 가 사용자 앱을 배포할 때 DB 를 만들고 접속 정보를 앱에 넣어주는 방법.

## projectId

플랫폼에 등록된 프로젝트 하나를 가리키는 이름. 예: `blog`, `todo-app`. CI/CD 가 정한다.

같은 이름을 세 군데에서 그대로 쓴다.

| 쓰이는 곳 | 예시 | 용도 |
|---|---|---|
| 프로비저너 | `projectId: blog` | 이 프로젝트의 DB 가 이미 있는지 확인 (프로젝트당 DB 1개) |
| k3s namespace | `app-blog` | 이 프로젝트의 앱이 들어가는 공간 |
| k3s Secret | `app-blog` 안의 `db-env` | 이 프로젝트의 DB 접속 정보 |

필요한 이유
- 같은 프로젝트를 다시 배포할 때 DB 가 새로 생기면 데이터가 날아간다. 프로비저너는 projectId 로 기존 DB 를 알아보고 1개만 유지한다
- 프로젝트끼리 섞이지 않게 한다. blog 앱은 `app-blog` 안에서 blog 의 DB 정보만 받는다

규칙: **소문자, 숫자, `-` 만. 처음과 끝은 영숫자. 최대 40자**
- k3s namespace 이름이 소문자·숫자·`-` 만 허용하고 최대 63자라서, 그 규칙에 맞춘다
- 40자 제한은 `app-` 같은 접두어를 붙여도 63자를 넘지 않게 하려는 것
- 규칙에 안 맞으면 프로비저너가 400 으로 거부한다 (예: `My-Blog`, `my_blog`, `blog-`)

## databaseId

프로비저너가 DB 를 만들 때 발급하는 고유번호. 예: `1b626675-e4a1-4703-85e8-d2c750aa226a`

- 접속 정보 조회, 삭제는 이 번호로 한다
- 프로젝트 정보에 `databaseId` 로 저장해 둔다
- 잃어버려도 `GET /api/databases?projectId=blog` 로 다시 찾을 수 있다

| | projectId | databaseId |
|---|---|---|
| 예시 | `blog` | `1b626675-...` |
| 정하는 쪽 | CI/CD | 프로비저너 |
| 용도 | 프로젝트 이름, k3s 이름 | DB 조회·삭제 |

## 호출

- 주소: `http://db-provisioner.lily-system.svc` (클러스터 내부 전용)
- 헤더: `Authorization: Bearer {PROVISIONER_API_TOKEN}`

### 1. 프로젝트 등록 시 (DB 가 필요한 경우만, 1회)

```
POST /api/databases
{"projectId":"blog","engine":"postgres"}
```
- 응답의 `id` 를 `databaseId` 로 저장
- 409 `ALREADY_EXISTS`: 이미 있음. `GET /api/databases?projectId=blog` 로 id 조회

### 2. 배포할 때마다

```
GET /api/databases/{databaseId}/env
```
- 응답의 `env` 를 그대로 Secret 으로 만든다 (`app-{projectId}` namespace 의 `db-env`)
- 앱 Deployment 에 `envFrom: secretRef: db-env` 로 주입
- **응답에 비밀번호가 있으므로 로그에 출력하지 않는다**
- 409 `NOT_READY`: DB 가 아직 사용 가능 상태가 아님. 상태가 `FAILED` 면 같은 projectId 로 POST 재시도
- 스키마 마이그레이션은 앱이 기동하면서 한다 (Flyway, Prisma 등). 파이프라인에서 따로 돌리지 않는다

### 3. 프로젝트 삭제 시

```
DELETE /api/databases/{databaseId}
```

## 주입되는 환경변수

| 변수 | 대상 |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | 일반 Spring Boot 앱. 코드 수정 없이 인식 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | Spring 커넥션 풀 크기 (5) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_POOL_SIZE` | `lily-blog-sample` 규칙 |
| `DATABASE_URL` | Node(Prisma 등), Python |

## 레플리카

- 앱당 **최대 2개**
- DB 계정당 동시 연결은 20개로 제한된다. 블루-그린 전환 중에는 구버전과 신버전이 같이 떠서 최대 4개 Pod x 풀 5 = 20
- 레플리카를 늘리려면 풀 크기(`APP_DB_POOL_SIZE`)나 연결 제한(`DB_CONNECTION_LIMIT`)을 같이 조정해야 한다
