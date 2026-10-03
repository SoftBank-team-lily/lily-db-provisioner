resource "random_password" "postgres" {
  length  = 32
  special = false
}

resource "random_password" "mysql" {
  count   = var.enable_mysql ? 1 : 0
  length  = 32
  special = false
}

resource "google_sql_database_instance" "postgres" {
  name                = "${var.name}-postgres"
  database_version    = "POSTGRES_16"
  region              = var.region
  deletion_protection = true

  settings {
    tier = var.sql_tier

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.lily.id
    }

    backup_configuration {
      enabled = true
    }
  }

  depends_on = [google_service_networking_connection.sql]
}

resource "google_sql_user" "postgres" {
  name     = "lily_admin"
  instance = google_sql_database_instance.postgres.name
  password = random_password.postgres.result
}

resource "google_sql_database_instance" "mysql" {
  count               = var.enable_mysql ? 1 : 0
  name                = "${var.name}-mysql"
  database_version    = "MYSQL_8_4"
  region              = var.region
  deletion_protection = true

  settings {
    tier = var.sql_tier

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.lily.id
    }

    backup_configuration {
      enabled = true
    }
  }

  depends_on = [google_service_networking_connection.sql]
}

resource "google_sql_user" "mysql" {
  count    = var.enable_mysql ? 1 : 0
  name     = "lily_admin"
  instance = google_sql_database_instance.mysql[0].name
  password = random_password.mysql[0].result
}
