resource "google_artifact_registry_repository" "apps" {
  location      = var.region
  repository_id = "${var.name}-apps"
  format        = "DOCKER"
  description   = "사용자 앱 이미지. Kaniko 가 빌더 클러스터에서 푸시한다"
  depends_on    = [google_project_service.required]
}

resource "google_service_account" "kaniko" {
  account_id   = "${var.name}-kaniko"
  display_name = "Lily Kaniko push"
  depends_on   = [google_project_service.required]
}

resource "google_artifact_registry_repository_iam_member" "kaniko" {
  location   = google_artifact_registry_repository.apps.location
  repository = google_artifact_registry_repository.apps.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.kaniko.email}"
}

resource "google_service_account_key" "kaniko" {
  service_account_id = google_service_account.kaniko.name
}

resource "local_sensitive_file" "kaniko_key" {
  filename        = "${path.module}/kaniko-key.json"
  file_permission = "0600"
  content         = base64decode(google_service_account_key.kaniko.private_key)
}

resource "random_password" "provisioner_token" {
  length  = 32
  special = false
}
