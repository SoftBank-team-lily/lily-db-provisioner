data "google_compute_image" "debian" {
  family  = "debian-12"
  project = "debian-cloud"
}

# 노드에는 프로젝트 API 권한을 주지 않는다. 이미지 푸시는 kaniko 서비스 계정 키로만 한다
resource "google_service_account" "nodes" {
  account_id   = "${var.name}-k3s"
  display_name = "Lily k3s nodes"
  depends_on   = [google_project_service.required]
}

resource "random_password" "k3s" {
  length  = 48
  special = false
}

resource "google_compute_address" "ingress" {
  name   = "${var.name}-ingress"
  region = var.region
}

resource "google_compute_address" "bastion" {
  name   = "${var.name}-bastion"
  region = var.region
}

resource "google_compute_address" "server" {
  name         = "${var.name}-k3s-server"
  region       = var.region
  address_type = "INTERNAL"
  subnetwork   = google_compute_subnetwork.nodes.id
}

resource "google_compute_instance" "server" {
  name         = "${var.name}-k3s-server"
  machine_type = "e2-medium"
  zone         = "${var.region}-a"
  tags         = ["${var.name}-k3s"]

  boot_disk {
    initialize_params {
      image = data.google_compute_image.debian.self_link
      size  = 30
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.nodes.id
    network_ip = google_compute_address.server.address
  }

  metadata = {
    startup-script = templatefile("${path.module}/startup-server.sh.tftpl", {
      token          = random_password.k3s.result
      ingress_ip     = google_compute_address.ingress.address
      manifest       = var.ingress_nginx_manifest
      node_port_http = 30080
      node_port_https = 30443
    })
  }

  service_account {
    email  = google_service_account.nodes.email
    scopes = ["https://www.googleapis.com/auth/logging.write"]
  }

  depends_on = [google_compute_router_nat.nodes]
}

resource "google_compute_instance" "worker" {
  count        = 2
  name         = "${var.name}-k3s-worker-${count.index + 1}"
  machine_type = "e2-medium"
  zone         = "${var.region}-a"
  tags         = ["${var.name}-k3s"]

  boot_disk {
    initialize_params {
      image = data.google_compute_image.debian.self_link
      size  = 30
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.nodes.id
  }

  metadata = {
    startup-script = templatefile("${path.module}/startup-worker.sh.tftpl", {
      token           = random_password.k3s.result
      server_ip       = google_compute_address.server.address
      node_port_http  = 30080
      node_port_https = 30443
    })
  }

  service_account {
    email  = google_service_account.nodes.email
    scopes = ["https://www.googleapis.com/auth/logging.write"]
  }

  depends_on = [google_compute_instance.server, google_compute_router_nat.nodes]
}

resource "google_compute_instance" "bastion" {
  name         = "${var.name}-bastion"
  machine_type = "e2-small"
  zone         = "${var.region}-a"
  tags         = ["${var.name}-bastion"]

  boot_disk {
    initialize_params {
      image = data.google_compute_image.debian.self_link
      size  = 10
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.nodes.id
    access_config {
      nat_ip = google_compute_address.bastion.address
    }
  }

  metadata = {
    startup-script = templatefile("${path.module}/startup-bastion.sh.tftpl", {
      ca_public_key = var.tunnel_ca_public_key
      postgres_ip   = google_sql_database_instance.postgres.private_ip_address
      mysql_ip      = var.enable_mysql ? google_sql_database_instance.mysql[0].private_ip_address : ""
    })
  }

  service_account {
    email  = google_service_account.nodes.email
    scopes = ["https://www.googleapis.com/auth/logging.write"]
  }
}

resource "google_compute_target_pool" "ingress" {
  name   = "${var.name}-ingress"
  region = var.region

  instances = concat(
    [google_compute_instance.server.self_link],
    google_compute_instance.worker[*].self_link,
  )

  health_checks = [google_compute_http_health_check.ingress.name]
}

resource "google_compute_http_health_check" "ingress" {
  name         = "${var.name}-ingress"
  port         = 80
  request_path = "/healthz"
}

resource "google_compute_forwarding_rule" "ingress" {
  name       = "${var.name}-ingress"
  region     = var.region
  ip_address = google_compute_address.ingress.address
  port_range = "80"
  target     = google_compute_target_pool.ingress.id
}
