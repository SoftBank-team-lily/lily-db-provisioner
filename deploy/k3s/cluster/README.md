# 클러스터 공용 설정

플랫폼 모듈(db-provisioner, lily-cicd, lily-builder)과 사용자 앱이 k3s 에서 돌기 위해 클러스터에 한 번 적용한 것들.
2026-09-30 기준 실제 클러스터(lily-server + worker 2대)에 적용되어 있다.

| 항목 | 내용 | 적용 방법 |
|---|---|---|
| ECR pull 인증 | `ecr-credentials` CronJob 이 6시간마다 노드 IAM 역할로 ECR 토큰을 받아 `lily-system`, `lily-builds`, `default` 의 Secret `ecr-pull` 을 갱신하고 각 namespace 의 ServiceAccount 에 imagePullSecrets 로 붙인다 | `kubectl apply -f ecr-credentials.yaml` 후 `kubectl -n kube-system create job ecr-credentials-init --from=cronjob/ecr-credentials` |
| Ingress | ingress-nginx `controller-v1.12.1` (cloud 매니페스트). k3s servicelb 가 노드 80/443 으로 노출. traefik 은 꺼져 있다 | `kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/cloud/deploy.yaml` |
| 앱 도메인 | lily-cicd `LILY_DEPLOY_DOMAIN=43.200.152.53.nip.io` → 앱 주소 `http://{appName}.43.200.152.53.nip.io` | `kubectl -n lily-system set env deploy/lily-cicd LILY_DEPLOY_DOMAIN=...` |
| lily-server 역할 | lily-server 만 `lily-server-role` (ECR PowerUser, CloudWatch, `lily-db-provisioner`, `lily-builder`). worker 는 기존 `lily-ec2-role` 유지 | `infra/` 에서 `create_server_role = true` 로 apply 후 `aws ec2 replace-iam-instance-profile-association` |

## 주의

- ECR 토큰 갱신은 **이미 있는 ServiceAccount** 에만 붙는다. namespace 나 ServiceAccount 를 새로 만들면 Job 을 한 번 다시 돌린다
- 사용자 앱을 `default` 가 아닌 namespace 에 배포하려면 `ecr-credentials.yaml` 의 `NAMESPACES` 에 추가한다
- worker 역할(`lily-ec2-role`)에는 `AmazonDynamoDBFullAccess` 가 붙어 있고 IMDS hop limit 이 2 라서, worker 에서 도는 사용자 앱 Pod 가 플랫폼 DynamoDB 테이블에 접근할 수 있다. worker 역할에서 DynamoDB 권한을 빼거나 hop limit 을 1 로 낮추는 것을 권장 (Kaniko 의 ECR push 는 worker 역할의 ECR 권한만 필요)
- 앱별 ECR 저장소는 lily-builder 가 빌드 전에 만든다 (`lily-builder` 정책의 `ecr:CreateRepository`)
