resource "google_compute_network" "lily" {
  name                    = var.name
  auto_create_subnetworks = false
  depends_on              = [google_project_service.required]
}

resource "google_compute_subnetwork" "nodes" {
  name                     = "${var.name}-nodes"
  ip_cidr_range            = "10.10.0.0/20"
  region                   = var.region
  network                  = google_compute_network.lily.id
  private_ip_google_access = true
}

resource "google_compute_router" "nodes" {
  name    = "${var.name}-nodes"
  region  = var.region
  network = google_compute_network.lily.id
}

resource "google_compute_router_nat" "nodes" {
  name                               = "${var.name}-nodes"
  router                             = google_compute_router.nodes.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

resource "google_compute_firewall" "internal" {
  name    = "${var.name}-k3s-internal"
  network = google_compute_network.lily.name

  allow {
    protocol = "all"
  }

  source_tags = ["${var.name}-k3s"]
  target_tags = ["${var.name}-k3s"]
}

resource "google_compute_firewall" "ingress_http" {
  name    = "${var.name}-ingress-http"
  network = google_compute_network.lily.name

  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["${var.name}-k3s"]
}

resource "google_compute_firewall" "bastion_ssh" {
  name    = "${var.name}-bastion-ssh"
  network = google_compute_network.lily.name

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = var.ssh_source_ranges
  target_tags   = ["${var.name}-bastion"]
}

# Cloud SQL 사설 IP 용 서비스 네트워킹 피어링
resource "google_compute_global_address" "sql" {
  name          = "${var.name}-sql-peering"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.lily.id
  depends_on    = [google_project_service.required]
}

resource "google_service_networking_connection" "sql" {
  network                 = google_compute_network.lily.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql.name]
  depends_on              = [google_project_service.required]
}
