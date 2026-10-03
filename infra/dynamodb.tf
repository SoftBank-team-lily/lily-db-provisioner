# 플랫폼 메타데이터 (프로비저너 전용 테이블)
#
# 프로젝트당 DB 하나를 PROJECT#{projectId} 가드 항목으로 강제한다. 그래서 provisioner 마다 테이블을 따로 둔다.
# 같은 테이블을 쓰면 같은 앱 이름으로 RDS 와 Cloud SQL 에 DB 를 동시에 둘 수 없다 (앱을 다른 클라우드로 옮길 때 필요).
#   managed_databases     : GCP provisioner (Cloud SQL). 처음에는 AWS 와 같이 썼다
#   managed_databases_aws : AWS provisioner (RDS). 2026-10-04 에 RDS 기록을 옮겨 왔다
resource "aws_dynamodb_table" "managed_databases" {
  name         = "${var.name}-managed-databases"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}

resource "aws_dynamodb_table" "managed_databases_aws" {
  name         = "${var.name}-managed-databases-aws"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}
