# infra — 공용 RDS + 메타데이터 테이블

프로비저너를 실제 AWS 에서 검증하기 위한 최소 인프라.
Terraform / AWS CLI 는 Docker 로 실행하므로 로컬 설치가 필요 없다 (`tf.sh`, `aws.sh`).
`tf.sh` 는 provider 바이너리를 Docker 볼륨(`lily-tfdata`)에 둔다. OneDrive 같은 동기화 폴더에 두면 플러그인 기동이 타임아웃난다.

## 만드는 것

| 리소스 | 내용 | 비용 (서울, 추정) |
|---|---|---|
| RDS PostgreSQL 16 | `db.t4g.micro`, 단일 AZ, gp3 20GB, 암호화 | 약 $0.025/h + 스토리지 |
| RDS MySQL 8.4 | `enable_mysql = true` 일 때만 | 위와 같음 |
| 보안그룹 | 5432/3306 을 `allowed_cidrs`(테스트 PC) 와 `allowed_security_group_ids`(k3s 노드) 에만 허용 | 무료 |
| DynamoDB `lily-managed-databases` | PAY_PER_REQUEST, pk(S) | 테스트 수준 ≈ $0 |
| IAM 정책 `lily-db-provisioner` | 프로비저너 최소 권한. `provisioner_role_name` 을 넣으면 그 역할(lily-server)에 연결 | 무료 |
| DynamoDB `lily-builds` + IAM 정책 `lily-builder` | lily-builder 배포 이력. 정책은 같은 lily-server 역할에 연결 | 테스트 수준 ≈ $0 |
| Budgets | `budget_email` 을 넣으면 월 예산 알림 | 무료 |

- 기본 VPC 를 쓴다. 플랫폼 VPC 가 생기면 서브넷/보안그룹만 바꾸면 된다
- 테스트 편의를 위해 `publicly_accessible = true` + 내 IP 만 허용. 검증이 끝나면 `false` 로 돌리거나 destroy
- 관리자 비밀번호는 Terraform state 와 `.env.aws` 에만 있다. 둘 다 gitignore — **절대 커밋하지 않는다**

## 실행 순서

### 1. AWS 자격증명 (1회)

루트 계정의 액세스 키는 만들지 않는다. 콘솔에서 IAM 사용자를 만든다.

1. IAM → 사용자 → 사용자 생성 (예: `hyunsu`)
2. 권한: `AdministratorAccess` 직접 연결 (해커톤 기간 한정)
3. 사용자 → 보안 자격 증명 → 액세스 키 만들기 → "CLI" 선택
4. 로컬에 등록:
   ```bash
   ./aws.sh configure          # Access key / Secret / region: ap-northeast-2 / output: json
   ./aws.sh sts get-caller-identity   # 계정 ID 와 사용자 확인
   ```

### 2. 변수

```bash
cp terraform.tfvars.example terraform.tfvars
curl https://checkip.amazonaws.com        # 이 IP 를 allowed_cidrs 에 "x.x.x.x/32" 로
```

k3s 클러스터에서 접속하려면 server/worker EC2 의 보안그룹 ID 를 `allowed_security_group_ids` 에 넣는다.
보안그룹 참조는 **같은 VPC** 안에서만 동작한다 (RDS 는 기본 VPC). EC2 가 다른 VPC 면 VPC 피어링이나 RDS 이전이 필요하다.

### 3. 생성 (약 5~10분, RDS 생성 대기)

```bash
./tf.sh init
./tf.sh plan -out=plan.tfplan     # 만들어질 리소스 확인
./tf.sh apply plan.tfplan
```

끝나면 `.env.aws` 가 생긴다 (프로비저너 접속 설정, 관리자 비밀번호 포함).

### 4. 검증

```bash
./smoke-test.sh
```

프로비저너를 `prod` 프로파일로 띄워서 실제 RDS / DynamoDB / SSM 에 붙이고 확인한다.
- RDS 마스터 계정으로 `CREATE ROLE` → `GRANT ... TO CURRENT_USER` → `CREATE DATABASE ... OWNER` 가 되는지
- 비밀번호가 SSM SecureString 에 저장되는지
- 테넌트 계정으로 자기 DB 사용 / 다른 테넌트 DB · 기본 `postgres` DB 접속 거부 / 틀린 비밀번호 거부
- (`lily-blog-sample` 이미지가 있으면) 샘플 앱 배포 + Flyway
- 삭제 시 `DROP DATABASE WITH (FORCE)` / `DROP ROLE` / SSM / DynamoDB 정리

### 5. 정리

```bash
./tf.sh destroy
```

중간에 쉬는 동안 비용을 줄이려면 콘솔에서 RDS 를 "일시 중지" 해도 된다 (스토리지 비용만 나감, 7일 후 자동 재시작).
