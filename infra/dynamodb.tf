# 플랫폼 메타데이터 (프로비저너 전용 테이블)
resource "aws_dynamodb_table" "managed_databases" {
  name         = "${var.name}-managed-databases"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}
