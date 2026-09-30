# k3s 배포

db-provisioner 를 k3s 클러스터의 **lily-server 노드**에 올린다. 클러스터 내부에서만 접근한다.

```
CI/CD (클러스터 안)  →  http://db-provisioner.lily-system.svc  →  공용 RDS / DynamoDB / SSM
```

## 왜 lily-server 인가

프로비저너는 AWS 권한(DynamoDB, SSM)을 EC2 인스턴스 역할로 받는다.
인스턴스 역할은 그 노드의 **모든 Pod** 가 쓸 수 있으므로, worker 에 붙이면 사용자 앱이 SSM 에 저장된 다른 프로젝트 DB 비밀번호를 읽을 수 있다.
그래서 역할은 lily-server 에만 붙이고, 프로비저너도 lily-server 에서만 실행한다 (`nodeSelector`).

## 사전 준비 (AWS 콘솔)

1. **같은 VPC 인지 확인**: k3s EC2 와 RDS(기본 VPC) 가 같은 VPC 여야 한다
2. **IAM 역할**: lily-server EC2 에 인스턴스 역할이 없으면 만들어서 연결한다 (EC2 → 인스턴스 → 작업 → 보안 → IAM 역할 수정)
3. **IMDS hop limit 2**: Pod 에서 인스턴스 역할 자격증명을 받으려면 필요하다
   ```bash
   aws ec2 modify-instance-metadata-options --instance-id <lily-server ID> --http-put-response-hop-limit 2 --http-endpoint enabled
   ```
   worker 는 1 로 둔다 (worker 에는 역할도 붙이지 않는다)
4. **Terraform 변수** (`infra/terraform.tfvars`) 후 `./tf.sh apply`
   ```hcl
   allowed_security_group_ids = ["<lily-server SG>", "<worker SG>"]  # RDS 접속 허용
   provisioner_role_name      = "<lily-server 인스턴스 역할 이름>"      # 프로비저너 정책 연결
   ```

## 배포

```bash
# 1. 이미지 빌드 → ECR
docker build -t <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/lily-db-provisioner:latest .
docker push <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/lily-db-provisioner:latest

# 2. 설정 Secret (infra/.env.aws 는 tf apply 가 만든 파일. 관리자 비밀번호 포함, 커밋 금지)
kubectl create namespace lily-system
kubectl -n lily-system create secret generic db-provisioner-env --from-env-file=infra/.env.aws

# 3. 매니페스트의 image 를 ECR 주소로 바꾼 뒤
kubectl apply -f deploy/k3s/db-provisioner.yaml

# 4. 확인
kubectl -n lily-system get pods -o wide        # NODE 가 lily-server 인지
kubectl -n lily-system logs deploy/db-provisioner
kubectl -n lily-system exec deploy/db-provisioner -- wget -qO- localhost:8080/actuator/health
```

- ECR 에서 이미지를 받으려면 k3s 에 ECR 인증이 필요하다 (사용자 앱 배포와 같은 문제라 CI/CD 모듈과 같은 방식으로 맞춘다)
- `.env.aws` 가 바뀌면 Secret 을 지우고 다시 만든 뒤 `kubectl -n lily-system rollout restart deploy/db-provisioner`

## CI/CD 에서 호출

```bash
curl -H "Authorization: Bearer $PROVISIONER_API_TOKEN" http://db-provisioner.lily-system.svc/api/databases/{id}/env
```

응답의 `env` 를 사용자 앱의 Secret 으로 만들어 Deployment 에 `envFrom` 으로 주입한다. 토큰은 `.env.aws` 의 `PROVISIONER_API_TOKEN`.
