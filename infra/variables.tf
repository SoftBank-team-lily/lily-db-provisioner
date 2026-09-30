variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "name" {
  description = "리소스 이름 접두어"
  type        = string
  default     = "lily"
}

variable "allowed_cidrs" {
  description = "RDS 5432/3306 에 접속을 허용할 CIDR (테스트 PC 의 공인 IP/32)"
  type        = list(string)
  default     = []
}

variable "allowed_security_group_ids" {
  description = "RDS 5432/3306 에 접속을 허용할 보안그룹 ID (k3s server/worker EC2 의 보안그룹). RDS 와 같은 VPC 여야 한다"
  type        = list(string)
  default     = []
}

variable "publicly_accessible" {
  description = "RDS 퍼블릭 접속. 로컬 PC 에서 테스트할 때만 true"
  type        = bool
  default     = true
}

variable "enable_postgres" {
  type    = bool
  default = true
}

variable "enable_mysql" {
  description = "MySQL 공용 인스턴스도 띄울지 (비용 2배)"
  type        = bool
  default     = false
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "GB. gp3 최소 20"
  type        = number
  default     = 20
}

variable "budget_email" {
  description = "월 예산 알림 받을 이메일. 비우면 예산 알림을 만들지 않음"
  type        = string
  default     = ""
}

variable "budget_limit_usd" {
  description = "월 예산 (USD)"
  type        = number
  default     = 20
}

variable "provisioner_role_name" {
  description = "프로비저너 정책을 붙일 IAM 역할 이름 (lily-server EC2 의 인스턴스 역할). 비우면 붙이지 않음"
  type        = string
  default     = ""
}
