# CI/CD 연동 규칙

lily-cicd 가 앱을 배포할 때 DB 를 준비하고 접속 정보를 앱 환경변수로 넣는 방법.

## 연결 지점

lily-cicd 의 `DatabaseProvisioner` 인터페이스. 배포할 때마다 새 슬롯(blue/green)을 만들기 전에 호출되고,
돌려준 맵이 컨테이너 환경변수로 들어간다. 구현체는 lily-cicd 레포의 `HttpDatabaseProvisioner`.

배포 요청(`POST /api/deployments`)의 `database` 에 `postgres` 또는 `mysql` 을 넣은 앱만 DB 를 준비한다.
생략하면 (프론트엔드 등) 프로비저너를 부르지 않고 DB 없이 배포한다.

```
POST /api/deployments (lily-cicd)   {"appName":"blog", ..., "database":"postgres"}
  → DatabaseProvisioner.prepare(context)             database 가 없으면 여기서 끝 (빈 env)
      1. GET  /api/databases?projectId={appName}   있으면 그대로 사용
      2. POST /api/databases                       없거나 FAILED 면 생성
      3. GET  /api/databases/{id}/env              접속 정보
  → 반환한 env 를 새 슬롯 Deployment 환경변수로 주입
  → blue-green 전환
```

- blue 와 green 은 같은 DB 를 쓴다. 두 번째 배포부터는 1번에서 기존 DB 를 찾아서 그대로 쓴다
- databaseId 를 따로 저장할 필요 없다. 매번 projectId 로 조회한다
- DB 준비에 실패하면 lily-cicd 가 Deployment 를 만들지 않고 배포를 중단한다

## projectId = appName

lily-cicd 의 `appName` 을 그대로 projectId 로 쓴다.

- appName 은 `{appName}-blue`, `{appName}-svc` 처럼 k3s 리소스 이름에 쓰인다
- 프로비저너는 projectId 로 "이 앱의 DB 가 이미 있는지" 를 판단한다 (앱당 DB 1개)
- 규칙은 appName 과 같다: **소문자, 숫자, `-` 만. 처음과 끝은 영숫자. 최대 55자**

| | projectId (= appName) | databaseId |
|---|---|---|
| 예시 | `blog` | `1b626675-...` |
| 정하는 쪽 | lily-cicd 배포 요청 | 프로비저너 |
| 용도 | 앱 이름, DB 조회 기준 | 접속 정보 조회, 삭제 |

## lily-cicd 설정

`application.yml` 또는 환경변수. `provisioner-url` 이 없으면 `database` 를 넣어도 DB 를 만들지 않는다 (`NoopDatabaseProvisioner`).

| 설정 | 환경변수 | 예시 |
|---|---|---|
| `lily.database.provisioner-url` | `LILY_DATABASE_PROVISIONER_URL` | `http://db-provisioner.lily-system.svc` |
| `lily.database.api-token` | `LILY_DATABASE_API_TOKEN` | 프로비저너의 `PROVISIONER_API_TOKEN` |

- `db-provisioner.lily-system.svc` 는 클러스터 안(Pod)에서만 풀리는 주소다. lily-cicd 를 노드 호스트에서 직접 실행하면 이 주소로는 접근할 수 없다

## 주입되는 환경변수

| 변수 | 대상 |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | 일반 Spring Boot 앱. 코드 수정 없이 인식 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | Spring 커넥션 풀 크기 (3) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_POOL_SIZE` | `lily-blog-sample` 규칙 |
| `DATABASE_URL` | Node(Prisma 등), Python |

- 배포 요청의 `extraEnv` 에 같은 키가 있으면 `extraEnv` 가 이긴다 (lily-cicd 규칙)
- 스키마 마이그레이션은 앱이 기동하면서 한다 (Flyway, Prisma 등)

## 커넥션 수

- DB 계정당 동시 연결 20개 제한. 앱 커넥션 풀은 기동할 때 연결을 미리 열기 때문에 (Hikari 기본값) Pod 수 x 풀 크기가 20 을 넘으면 넘친 Pod 는 DB 접속에 실패한다
- 그래서 풀 크기를 3 으로 내려준다

| 전략 | 전환 중 최대 Pod | 연결 수 |
|---|---|---|
| blue-green | 2 (blue 1 + green 1) | 2 x 3 = 6 |
| canary | 6 (stable 5 + canary 1, stable 을 줄이기 전) | 6 x 3 = 18 |

- lily-cicd 의 Pod 수(`TOTAL_REPLICAS`, 슬롯 레플리카)를 늘리면 풀 크기(`APP_DB_POOL_SIZE`)나 연결 제한(`DB_CONNECTION_LIMIT`)을 같이 조정해야 한다

## 아직 lily-cicd 쪽에 없는 것

- 프로젝트 삭제 시 `DELETE /api/databases/{id}` 호출
