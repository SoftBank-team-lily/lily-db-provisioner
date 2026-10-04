# 클러스터 공용 설정

플랫폼 모듈(db-provisioner, lily-cicd, lily-builder)과 사용자 앱이 k3s 에서 돌기 위해 클러스터에 한 번 적용한 것들.
2026-09-30 기준 실제 클러스터(lily-server + worker 2대)에 적용되어 있다. 권한이 어떻게 나뉘는지는 [docs/permissions.md](../../../docs/permissions.md).

| 항목 | 내용 | 적용 방법 |
|---|---|---|
| ECR 인증 | `ecr-credentials` CronJob 이 6시간마다 lily-server 역할로 ECR 토큰을 받아 `lily-system`, `lily-builds`, `default` 에 Secret `ecr-pull` 을 갱신하고 ServiceAccount 에 imagePullSecrets 로 붙인다. Kaniko 도 이 Secret 으로 push 한다 | `kubectl apply -f ecr-credentials.yaml` 후 `kubectl -n kube-system create job ecr-credentials-init --from=cronjob/ecr-credentials` |
| 플랫폼 API 격리 | `lily-system` 으로 들어오는 트래픽은 같은 namespace 와 노드에서만 허용 | `kubectl apply -f lily-system-network-policy.yaml` |
| Ingress | ingress-nginx `controller-v1.12.1` (cloud 매니페스트). k3s servicelb 가 노드 80/443 으로 노출. traefik 은 꺼져 있다 | `kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/cloud/deploy.yaml` |
| 앱 도메인 | lily-cicd `LILY_DEPLOY_DOMAIN=43.200.152.53.nip.io` → `http://{appName}.43.200.152.53.nip.io` | `kubectl -n lily-system set env deploy/lily-cicd LILY_DEPLOY_DOMAIN=...` |
| lily-server 전용 | taint `node-role.kubernetes.io/control-plane:NoSchedule`. AWS 가 필요한 플랫폼 Pod 만 toleration 으로 올라간다 | `kubectl taint node <lily-server> node-role.kubernetes.io/control-plane=true:NoSchedule` |
| lily-server 역할 | `lily-server-role` (ECR PowerUser, CloudWatch, `lily-db-provisioner`, `lily-builder`) | `infra/` 에서 `create_server_role = true` 로 apply 후 `aws ec2 replace-iam-instance-profile-association` |
| worker AWS 차단 | IMDS hop limit 1, 역할(`lily-ec2-role`)은 CloudWatch 만 | `aws ec2 modify-instance-metadata-options --http-put-response-hop-limit 1`, `aws iam detach-role-policy` |
| GCP DB 릴레이 | 멀티클라우드 앱(AWS + GCP)의 AWS 쪽 Pod 가 GCP Cloud SQL 에 붙는 `db-relay-gcp` (`lily-builds`, replicas 2). GCP 배스천으로 `ssh -L` 을 열어 둔다. 키·인증서는 lily-builder 가 Secret `db-relay-gcp` 에 두고 6시간마다 다시 서명한다. `default` namespace 에서만 붙는다 | `kubectl apply -f db-relay-gcp.yaml` (builder 가 Secret 을 만든 뒤 Ready) |

## 주의

- ECR Secret 은 **이미 있는 ServiceAccount** 에만 붙는다. namespace 나 ServiceAccount 를 새로 만들면 Job 을 한 번 다시 돌린다
- 사용자 앱을 `default` 가 아닌 namespace 에 배포하려면 `ecr-credentials.yaml` 의 `NAMESPACES` 에 추가한다
- AWS 권한이 필요한 Pod 를 새로 만들면 lily-server 에 고정해야 한다 (`nodeSelector` + toleration). worker 에서는 자격증명을 받지 못한다
- 반대로 **사용자 코드를 실행하는 Pod 는 lily-server 에 두지 않는다** (빌드 포함). lily-server 역할로 모든 테넌트 DB 비밀번호를 읽을 수 있다
- 새 NetworkPolicy 가 붙은 Pod 는 규칙이 반영되기까지 몇 초 걸린다. 뜨자마자 요청하면 막힐 수 있다
