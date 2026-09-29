# 프로비저너 실행 역할에 붙일 최소 권한 정책.
# 지금은 만들어만 두고, EC2/ECS 에 배포할 때 역할에 연결한다
data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "provisioner" {
  statement {
    sid = "MetadataTable"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:Scan",
      "dynamodb:DescribeTable",
    ]
    resources = [aws_dynamodb_table.managed_databases.arn]
  }

  statement {
    sid = "TenantPasswords"
    actions = [
      "ssm:PutParameter",
      "ssm:GetParameter",
      "ssm:DeleteParameter",
    ]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/lily/db/*"]
  }
}

resource "aws_iam_policy" "provisioner" {
  name        = "${var.name}-db-provisioner"
  description = "db-provisioner: metadata table + tenant passwords in SSM"
  policy      = data.aws_iam_policy_document.provisioner.json
}
