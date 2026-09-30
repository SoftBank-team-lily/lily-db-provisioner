# 테스트 단계는 기본 VPC 를 쓴다. 플랫폼 VPC 가 생기면 그쪽 서브넷/보안그룹으로 교체
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

resource "aws_db_subnet_group" "shared" {
  name       = "${var.name}-shared-db"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_security_group" "shared_db" {
  name        = "${var.name}-shared-db"
  description = "shared tenant RDS (postgres/mysql)"
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  for_each = var.enable_postgres ? toset(var.allowed_cidrs) : toset([])

  security_group_id = aws_security_group.shared_db.id
  description       = "postgres from allowed cidr"
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_ingress_rule" "mysql" {
  for_each = var.enable_mysql ? toset(var.allowed_cidrs) : toset([])

  security_group_id = aws_security_group.shared_db.id
  description       = "mysql from allowed cidr"
  ip_protocol       = "tcp"
  from_port         = 3306
  to_port           = 3306
  cidr_ipv4         = each.value
}

# k3s 노드(프로비저너 Pod, 사용자 앱 Pod)에서 오는 접속. IP 대신 노드의 보안그룹으로 허용한다
resource "aws_vpc_security_group_ingress_rule" "postgres_from_sg" {
  for_each = var.enable_postgres ? toset(var.allowed_security_group_ids) : toset([])

  security_group_id            = aws_security_group.shared_db.id
  description                  = "postgres from k3s nodes"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_ingress_rule" "mysql_from_sg" {
  for_each = var.enable_mysql ? toset(var.allowed_security_group_ids) : toset([])

  security_group_id            = aws_security_group.shared_db.id
  description                  = "mysql from k3s nodes"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.shared_db.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
