# 프로비저너 실행 역할에 붙일 최소 권한 정책.
# provisioner_role_name 을 넣으면 그 역할에 연결한다 (맨 아래)
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
    # 옮기는 동안 되돌릴 수 있게 옛 테이블도 둔다 (GCP provisioner 는 따로 자격증명을 쓴다)
    resources = [aws_dynamodb_table.managed_databases_aws.arn, aws_dynamodb_table.managed_databases.arn]
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

# lily-server EC2 의 인스턴스 역할에 위 정책을 붙인다 (프로비저너 Pod 가 이 역할로 AWS 에 접근).
# worker 역할에는 붙이지 않는다: worker 에서 도는 사용자 앱 Pod 도 같은 권한을 얻게 된다
resource "aws_iam_role_policy_attachment" "provisioner" {
  count      = var.provisioner_role_name == "" ? 0 : 1
  role       = var.provisioner_role_name
  policy_arn = aws_iam_policy.provisioner.arn
}
