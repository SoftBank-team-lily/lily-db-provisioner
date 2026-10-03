# GCP 데이터 플레인

Lily 플랫폼이 GCP 쪽에 두고 오래 쓰는 바닥이다. 사용자 앱 배포는 여기서 하지 않는다. 앱 이미지는 빌더의 Kaniko 가, 배포는 이 클러스터에 올린 lily-cicd 가 맡는다.

만드는 것:

- VPC, Cloud NAT, k3s server 1대와 worker 2대, 앞단 주소 하나
- ingress-nginx (NodePort 30080/30443, 호스트의 80/443 으로 받는다)
- Cloud SQL Postgres 16. MySQL 8.4 는 `enable_mysql = true` 일 때만
- Artifact Registry `lily-apps` 와 Kaniko 푸시용 서비스 계정 키 `kaniko-key.json`
- Cloud SQL 로만 포워딩하는 배스천

`deletion_protection` 이 켜져 있다. 지우기 전에 그 값을 false 로 바꾸고 apply 한 뒤 destroy 한다.

## 하지 않는 것

`infra/` 의 AWS 루트를 운영 계정에 빈 state 로 apply 하지 않는다. 그 파일은 테스트용 RDS 를 새로 만든다. 이미 있는 RDS 를 코드에 붙이려면 import 가 먼저다.

이 디렉터리는 GCP 프로젝트가 생긴 뒤에 plan, apply 한다. 컨트롤 플레인(프론트, 빌더, DynamoDB)은 AWS 에 둔다.

## 실행

```bash
cp terraform.tfvars.example terraform.tfvars
./tf.sh init
./tf.sh plan -out=plan.tfplan
./tf.sh apply plan.tfplan
```

자격증명은 `gcloud auth application-default login` 으로 둔다. 끝나면 `.env.gcp` 와 `kaniko-key.json` 이 생긴다. 둘 다 gitignore 다.

`.env.gcp` 의 `GCP_CICD_URL` 과 `GCP_PROVISIONER_URL` 은 비어 있다. k3s 에 lily-cicd 와 프로비저너를 올린 뒤에 채운다. `kaniko-key.json` 은 빌더 클러스터 `lily-builds` 네임스페이스의 `gcp-pull` 시크릿이 된다. 배스천이 플랫폼 CA 를 믿게 하려면 `tunnel_ca_public_key` 에 그 공개키를 넣고 다시 apply 한다.
