output "postgres_endpoint" {
  value = var.enable_postgres ? aws_db_instance.postgres[0].address : null
}

output "mysql_endpoint" {
  value = var.enable_mysql ? aws_db_instance.mysql[0].address : null
}

output "dynamodb_table" {
  value = aws_dynamodb_table.managed_databases.name
}

output "provisioner_policy_arn" {
  value = aws_iam_policy.provisioner.arn
}

# 로컬에서 프로비저너를 실제 AWS 에 붙이기 위한 env 파일 (관리자 비밀번호 포함, gitignore 됨)
resource "local_sensitive_file" "provisioner_env" {
  filename        = "${path.module}/.env.aws"
  file_permission = "0600"
  content = join("\n", compact([
    "SPRING_PROFILES_ACTIVE=prod",
    "PROVISIONER_API_TOKEN=${random_password.api_token.result}",
    "AWS_REGION=${var.region}",
    "DYNAMODB_TABLE=${aws_dynamodb_table.managed_databases.name}",
    "SECRET_STORE=ssm",
    "SSM_PREFIX=/lily/db",
    var.enable_postgres ? "PG_ENABLED=true" : "",
    var.enable_postgres ? "PG_ADMIN_URL=jdbc:postgresql://${aws_db_instance.postgres[0].address}:5432/postgres" : "",
    var.enable_postgres ? "PG_ADMIN_USERNAME=${aws_db_instance.postgres[0].username}" : "",
    var.enable_postgres ? "PG_ADMIN_PASSWORD=${random_password.postgres_admin[0].result}" : "",
    var.enable_postgres ? "PG_PUBLIC_HOST=${aws_db_instance.postgres[0].address}" : "",
    var.enable_mysql ? "MYSQL_ENABLED=true" : "",
    var.enable_mysql ? "MYSQL_ADMIN_URL=jdbc:mysql://${aws_db_instance.mysql[0].address}:3306/" : "",
    var.enable_mysql ? "MYSQL_ADMIN_USERNAME=${aws_db_instance.mysql[0].username}" : "",
    var.enable_mysql ? "MYSQL_ADMIN_PASSWORD=${random_password.mysql_admin[0].result}" : "",
    var.enable_mysql ? "MYSQL_PUBLIC_HOST=${aws_db_instance.mysql[0].address}" : "",
  ]))
}

resource "random_password" "api_token" {
  length  = 32
  special = false
}
