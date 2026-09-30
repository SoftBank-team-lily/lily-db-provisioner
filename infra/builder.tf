# lily-builder 배포 이력 (플랫폼 DB). 레포는 lily-builder 지만 플랫폼 공용 인프라라 여기서 같이 만든다
resource "aws_dynamodb_table" "builds" {
  name         = "${var.name}-builds"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}

data "aws_iam_policy_document" "builder" {
  statement {
    sid = "BuildHistory"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:Scan",
      "dynamodb:DescribeTable",
    ]
    resources = [aws_dynamodb_table.builds.arn]
  }

  # 앱별 ECR 저장소를 빌드 전에 만든다 (없으면 Kaniko push 가 NAME_UNKNOWN 으로 실패)
  statement {
    sid       = "CreateAppRepositories"
    actions   = ["ecr:CreateRepository"]
    resources = ["arn:aws:ecr:${var.region}:${data.aws_caller_identity.current.account_id}:repository/*"]
  }
}

resource "aws_iam_policy" "builder" {
  name        = "${var.name}-builder"
  description = "lily-builder: build history table"
  policy      = data.aws_iam_policy_document.builder.json
}

# lily-builder 도 lily-server 에서 돈다. 프로비저너와 같은 역할에 붙인다
resource "aws_iam_role_policy_attachment" "builder" {
  count      = var.provisioner_role_name == "" ? 0 : 1
  role       = var.provisioner_role_name
  policy_arn = aws_iam_policy.builder.arn
}

output "builds_table" {
  value = aws_dynamodb_table.builds.name
}
