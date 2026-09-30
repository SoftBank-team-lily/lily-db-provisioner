# lily-server(control-plane) 전용 인스턴스 역할.
#
# 노드 3대가 같은 역할을 쓰면 worker 에서 도는 사용자 앱 Pod 도 IMDS 로 그 역할을 얻는다.
# 프로비저너(SSM 의 테넌트 비밀번호)와 빌더 권한은 여기에만 붙이고, 프로비저너·빌더는 lily-server 에서만 돈다.
# 인스턴스에 연결하는 것은 Terraform 밖에서 한 번 한다 (인스턴스가 이 state 에 없어서):
#   aws ec2 replace-iam-instance-profile-association --association-id <id> --iam-instance-profile Name=lily-server-role
resource "aws_iam_role" "server" {
  count = var.create_server_role ? 1 : 0

  name = "${var.name}-server-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_instance_profile" "server" {
  count = var.create_server_role ? 1 : 0

  name = "${var.name}-server-role"
  role = aws_iam_role.server[0].name
}

# 기존 노드 역할(lily-ec2-role)과 같은 AWS 관리형 정책 중 서버에 필요한 것 (Kaniko 가 이 노드에 뜨면 ECR push, CloudWatch 에이전트)
resource "aws_iam_role_policy_attachment" "server_managed" {
  for_each = var.create_server_role ? toset([
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser",
    "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy",
  ]) : toset([])

  role       = aws_iam_role.server[0].name
  policy_arn = each.value
}

resource "aws_iam_role_policy_attachment" "server_provisioner" {
  count = var.create_server_role ? 1 : 0

  role       = aws_iam_role.server[0].name
  policy_arn = aws_iam_policy.provisioner.arn
}

resource "aws_iam_role_policy_attachment" "server_builder" {
  count = var.create_server_role ? 1 : 0

  role       = aws_iam_role.server[0].name
  policy_arn = aws_iam_policy.builder.arn
}

output "server_instance_profile" {
  value = var.create_server_role ? aws_iam_instance_profile.server[0].name : null
}
