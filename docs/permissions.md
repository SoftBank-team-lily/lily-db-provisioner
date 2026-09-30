# 권한 구조

Team Lily 플랫폼(k3s + AWS)의 권한이 어디에 있고 어떻게 분리되어 있는지. 2026-09-30 실제 클러스터 기준.

## 원칙

1. **AWS 권한은 lily-server 노드에만 있다.** worker 노드의 Pod 는 AWS 자격증명을 얻을 수 없다
2. **사용자 코드는 worker 에서만 돈다.** 사용자 앱과 빌드(= 사용자 Dockerfile 실행) 모두 해당
3. **플랫폼 모듈 API 는 클러스터 내부 전용이고, 사용자 앱에서는 호출할 수 없다**
4. **사용자 앱 DB 는 프로젝트마다 계정이 분리되고, 비밀번호는 플랫폼만 안다**

```
                     ┌──────────────── lily-server (control-plane, taint) ────────────────┐
  AWS 권한 O         │ db-provisioner   lily-builder   ecr-credentials(CronJob)   시스템 Pod │  IMDS 허용 (hop 2)
  (lily-server-role) └────────────────────────────────────────────────────────────────────┘
                     ┌──────────────── lily-worker-1, lily-worker-2 ──────────────────────┐
  AWS 권한 X         │ 사용자 앱   Kaniko 빌드   lily-cicd   ingress-nginx                    │  IMDS 차단 (hop 1)
  (lily-ec2-role)    └────────────────────────────────────────────────────────────────────┘
```

---

## 1. 노드와 스케줄링

| 노드 | 인스턴스 역할 | IMDS hop limit | taint | 여기서 도는 것 |
|---|---|---|---|---|
| lily-server | `lily-server-role` | 2 (Pod 가 역할 사용 가능) | `node-role.kubernetes.io/control-plane:NoSchedule` | db-provisioner, lily-builder, ecr-credentials, k3s 시스템 Pod |
| lily-worker-1, 2 | `lily-ec2-role` | **1** (Pod 는 IMDS 에 닿지 못함) | 없음 | 사용자 앱, Kaniko 빌드, lily-cicd, ingress-nginx |

- **taint**: toleration 이 없는 Pod(사용자 앱 등)는 lily-server 에 스케줄되지 않는다. AWS 가 필요한 플랫폼 Pod 만 `nodeSelector` + toleration 으로 lily-server 에 고정한다
- **IMDS hop limit 1**: 인스턴스 메타데이터 응답이 컨테이너 네트워크를 한 번 더 거치지 못해서, worker 의 Pod 는 인스턴스 역할 자격증명을 받지 못한다. 노드 자체(호스트 프로세스)는 받을 수 있다
- 검증: 노드마다 Pod 를 띄워 IMDS 토큰을 요청 → worker 두 대 `NO_CREDENTIALS`, lily-server `lily-server-role`

## 2. AWS IAM

### lily-server-role (lily-server 인스턴스 역할)

| 정책 | 권한 | 범위 | 쓰는 곳 |
|---|---|---|---|
| `lily-db-provisioner` | `dynamodb:GetItem, PutItem, UpdateItem, DeleteItem, Scan, DescribeTable` | `lily-managed-databases` 테이블 | db-provisioner 메타데이터 |
| | `ssm:PutParameter, GetParameter, DeleteParameter` | `parameter/lily/db/*` | 테넌트 DB 비밀번호 |
| `lily-builder` | `dynamodb:GetItem, PutItem, Scan, DescribeTable` | `lily-builds` 테이블 | 빌드 이력 |
| | `ecr:CreateRepository` | 이 계정의 ECR 저장소 | 앱별 ECR 저장소 생성 |
| `AmazonEC2ContainerRegistryPowerUser` (AWS 관리형) | ECR 토큰 발급, pull/push | 계정 전체 | ecr-credentials CronJob 이 토큰 발급 |
| `CloudWatchAgentServerPolicy` (AWS 관리형) | 메트릭·로그 전송 | | CloudWatch 에이전트 |

### lily-ec2-role (worker 인스턴스 역할)

| 정책 | 비고 |
|---|---|
| `CloudWatchAgentServerPolicy` | 호스트의 CloudWatch 에이전트용. Pod 는 IMDS 차단으로 쓸 수 없다 |

- 2026-09-30 에 `AmazonDynamoDBFullAccess`, `AmazonEC2ContainerRegistryPowerUser` 를 뗐다. 이전에는 사용자 앱이 플랫폼 DynamoDB 테이블을 읽고 쓰거나 다른 앱의 ECR 이미지를 덮어쓸 수 있었다

### 사람

| 주체 | 권한 | 용도 |
|---|---|---|
| IAM 사용자 `hyunsu` | `AdministratorAccess` | Terraform, 운영 작업 (액세스 키는 로컬 `~/.aws`) |
| 루트 계정 | 전체 | 쓰지 않는 것을 원칙으로 한다 |

Terraform 정의: `infra/iam.tf`(프로비저너), `infra/builder.tf`(빌더), `infra/server-role.tf`(lily-server-role).

## 3. Kubernetes RBAC

| ServiceAccount | 범위 | 권한 |
|---|---|---|
| `lily-system/lily-cicd` | 클러스터 전체 (ClusterRole) | deployments: get/list/watch/create/update/patch/delete, deployments/scale: get/update/patch, services·ingresses: get/create/update/patch |
| `lily-system/lily-builder` | `lily-builds` namespace (Role) | jobs: create/get/list/watch/delete, secrets·configmaps: create/get/patch/delete, pods·pods/log: get/list/watch |
| | 클러스터 전체 (ClusterRole, 읽기) | deployments·services·ingresses: get/list ("배포된 앱" 화면) |
| `kube-system/ecr-credentials` | 클러스터 전체 (ClusterRole) | secrets: get/create/patch/delete, serviceaccounts: get/list/patch, namespaces: get |
| `lily-system/default` (db-provisioner) | 없음 | k8s API 를 쓰지 않는다 |
| 사용자 앱 (`default/default`) | 없음 | |

## 4. 네트워크

| 경로 | 허용 | 근거 |
|---|---|---|
| 인터넷 → 노드 80/443 | 허용 | 노드 보안그룹. ingress-nginx → 사용자 앱 |
| 인터넷 → 노드 22 | 허용 (키 인증) | 노드 보안그룹. 운영 접속 |
| 노드 ↔ 노드 | 전체 허용 | 노드 보안그룹 자기 참조 |
| **k3s 노드 → RDS 5432** | **허용** | RDS 보안그룹이 노드 보안그룹만 허용 |
| 인터넷 → RDS | **차단** | `publicly_accessible = false`, 로컬 PC IP 규칙 제거 |
| 사용자 앱 Pod → `lily-system` (cicd, builder, provisioner) | **차단** | NetworkPolicy `lily-system/allow-internal-only` |
| `lily-system` 내부 (builder → cicd → provisioner) | 허용 | 같은 NetworkPolicy |
| 노드 → `lily-system` Pod | 허용 | 같은 NetworkPolicy (`172.31.0.0/16`, kubelet 헬스체크) |

- 플랫폼 모듈 Service 는 모두 ClusterIP 라 외부로 노출되지 않는다. Ingress 가 붙는 것은 사용자 앱뿐
- 검증: `default` 에서 세 모듈 호출 → 모두 차단, `lily-system` 에서 호출 → 정상

## 5. 비밀값

| 비밀값 | 저장 위치 | 누가 읽을 수 있나 |
|---|---|---|
| RDS 마스터 비밀번호, 프로비저너 API 토큰 | k8s Secret `lily-system/db-provisioner-env`, 로컬 `infra/.env.aws`, Terraform state | db-provisioner, lily-cicd(토큰만), 클러스터 관리자 |
| 테넌트 DB 비밀번호 | SSM `/lily/db/{id}/password` (SecureString, KMS) | lily-server-role (db-provisioner) |
| 테넌트 접속 정보 (env) | 사용자 앱 Deployment 환경변수 | 해당 앱 |
| ECR 토큰 | k8s Secret `ecr-pull` (`lily-system`, `lily-builds`, `default`), 12시간 유효, 6시간마다 갱신 | 각 namespace 의 Pod (이미지 pull, Kaniko push) |
| private 레포 Git 토큰 | k8s Secret `lily-builds/build-{id}` | 해당 빌드의 Kaniko. 빌드가 끝나면 삭제 |
| AWS 액세스 키 (`hyunsu`) | 로컬 `~/.aws` | 작업자 PC |

- `.env.aws`, `terraform.tfstate`, `terraform.tfvars` 는 gitignore. 커밋하지 않는다

## 6. 사용자 앱 DB

| 항목 | 보장 |
|---|---|
| 계정 | 프로젝트마다 전용 계정. 이름은 서버가 생성 (`p_` + 16자리 hex) |
| 접근 범위 | 자기 DB 만. PostgreSQL 은 DB 별 CONNECT 권한 회수, MySQL 은 자기 스키마에만 GRANT |
| 기본 DB | `postgres`, `template1` 접속 불가 (프로비저너가 기동 시 PUBLIC CONNECT 회수) |
| 커넥션 | 계정당 20개 |
| 관리자 계정 | db-provisioner 만 사용. 사용자에게 나가지 않는다 |

자세한 내용은 [README 의 보장하는 동작](../README.md#2-보장하는-동작).

---

## 남은 위험

| 위험 | 영향 | 권장 대응 |
|---|---|---|
| ECR 토큰은 계정 단위 | 악의적인 Dockerfile 이 빌드 중 `ecr-pull` 토큰을 읽어 **다른 앱 저장소에 push** 할 수 있다 | 빌드 전용 IAM 역할을 따로 두고 저장소 단위로 제한 (토큰 발급 구조 변경 필요) |
| lily-cicd 의 ClusterRole | 클러스터 전체의 Deployment 를 만들고 지울 수 있다. cicd 가 탈취되면 `lily-system` 도 건드릴 수 있다 | 사용자 앱 namespace 만 Role 로 허용하고 ClusterRole 제거 |
| lily-cicd, lily-builder API 인증 없음 | 사용자 앱에서는 NetworkPolicy 로 막혔지만, `lily-system` 안의 Pod 나 노드에서는 호출 가능 | 프로비저너처럼 토큰 인증 추가 |
| 배포 요청의 namespace 지정 | cicd 에 namespace 를 넣으면 `lily-system` 에도 배포할 수 있다 (NetworkPolicy 안쪽으로 들어감) | cicd 에서 허용 namespace 를 제한 |
| 노드 22번 전체 공개 | 키 인증이라 당장 위험은 낮다 | 운영자 IP 로 제한하거나 SSM Session Manager 로 전환 |
| 루트 계정 공유 | 결제·계정 삭제까지 가능 | 팀원별 IAM 사용자로 전환, 루트는 MFA 걸고 봉인 |
