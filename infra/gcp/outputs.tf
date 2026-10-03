output "ingress_address" {
  value = google_compute_address.ingress.address
}

output "registry" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.apps.repository_id}"
}

output "postgres_private_ip" {
  value = google_sql_database_instance.postgres.private_ip_address
}

output "mysql_private_ip" {
  value = var.enable_mysql ? google_sql_database_instance.mysql[0].private_ip_address : null
}

output "bastion_address" {
  value = google_compute_address.bastion.address
}

# 빌더 환경변수. 비밀번호와 토큰이 있으므로 gitignore
resource "local_sensitive_file" "builder_env" {
  filename        = "${path.module}/.env.gcp"
  file_permission = "0600"
  content = join("\n", [
    "GCP_CICD_URL=",
    "GCP_REGISTRY=${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.apps.repository_id}",
    "GCP_REGISTRY_AUTH_SECRET=gcp-pull",
    "GCP_PROVISIONER_URL=",
    "GCP_PROVISIONER_API_TOKEN=${random_password.provisioner_token.result}",
    "GCP_BURST_INGRESS_HOST=${google_compute_address.ingress.address}",
    "GCP_BURST_INGRESS_PORT=80",
    "GCP_CUTOVER_ORIGIN=${google_compute_address.ingress.address}",
    "GCP_TUNNEL_SSH_HOST=${google_compute_address.bastion.address}",
    "GCP_TUNNEL_SSH_USER=lily-tunnel",
    "GCP_TUNNEL_REMOTE_HOST=${google_sql_database_instance.postgres.private_ip_address}",
    "GCP_TUNNEL_REMOTE_PORT=5432",
    "GCP_TUNNEL_REVERSE_HOST=${google_compute_instance.bastion.network_interface[0].network_ip}",
    "PG_ADMIN_USERNAME=lily_admin",
    "PG_ADMIN_PASSWORD=${random_password.postgres.result}",
    var.enable_mysql ? "MYSQL_ADMIN_PASSWORD=${random_password.mysql[0].result}" : "",
  ])
}
