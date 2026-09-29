# 엔진별 공용 인스턴스. 프로젝트별 DB/계정은 프로비저너가 이 안에 만든다
# 마스터 비밀번호는 state 에 저장된다 → state 파일은 절대 커밋하지 않는다

resource "random_password" "postgres_admin" {
  count   = var.enable_postgres ? 1 : 0
  length  = 32
  special = false
}

resource "aws_db_instance" "postgres" {
  count = var.enable_postgres ? 1 : 0

  identifier     = "${var.name}-shared-postgres"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  username = "lily_admin"
  password = random_password.postgres_admin[0].result

  db_subnet_group_name   = aws_db_subnet_group.shared.name
  vpc_security_group_ids = [aws_security_group.shared_db.id]
  publicly_accessible    = var.publicly_accessible
  multi_az               = false

  # 테스트 단계: 삭제가 바로 되도록
  backup_retention_period  = 1
  skip_final_snapshot      = true
  deletion_protection      = false
  delete_automated_backups = true
  apply_immediately        = true

  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}

resource "random_password" "mysql_admin" {
  count   = var.enable_mysql ? 1 : 0
  length  = 32
  special = false
}

resource "aws_db_instance" "mysql" {
  count = var.enable_mysql ? 1 : 0

  identifier     = "${var.name}-shared-mysql"
  engine         = "mysql"
  engine_version = "8.4"
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  username = "lily_admin"
  password = random_password.mysql_admin[0].result

  db_subnet_group_name   = aws_db_subnet_group.shared.name
  vpc_security_group_ids = [aws_security_group.shared_db.id]
  publicly_accessible    = var.publicly_accessible
  multi_az               = false

  backup_retention_period  = 1
  skip_final_snapshot      = true
  deletion_protection      = false
  delete_automated_backups = true
  apply_immediately        = true

  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}
