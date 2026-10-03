variable "project_id" {
  description = "GCP 프로젝트 ID. 결제와 API 사용이 켜져 있어야 한다"
  type        = string
}

variable "region" {
  description = "데이터 플레인 리전. 빌더의 기본 가정은 서울"
  type        = string
  default     = "asia-northeast3"
}

variable "name" {
  description = "리소스 이름 접두어"
  type        = string
  default     = "lily"
}

variable "ssh_source_ranges" {
  description = "배스천 22번을 열 CIDR. 에이전트 PC 와 빌더가 SSH 로 닿는 주소"
  type        = list(string)

  validation {
    condition     = length(var.ssh_source_ranges) > 0
    error_message = "배스천 SSH 를 열 CIDR 이 하나 이상 필요하다."
  }
}

variable "tunnel_ca_public_key" {
  description = "플랫폼 SSH CA 공개키 한 줄. 비우면 배스천 계정만 만들고 authorized_keys 는 나중에 넣는다"
  type        = string
  default     = ""
}

variable "enable_mysql" {
  description = "Cloud SQL MySQL 8.4 도 만들지. Postgres 는 항상 만든다"
  type        = bool
  default     = false
}

variable "sql_tier" {
  description = "Cloud SQL 머신 등급"
  type        = string
  default     = "db-g1-small"
}

variable "ingress_nginx_manifest" {
  description = "k3s 에 올릴 ingress-nginx baremetal 매니페스트"
  type        = string
  default     = "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/baremetal/deploy.yaml"
}
