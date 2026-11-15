# OpenBao — Scanova's key vault (Transit engine). docs/ARCHITECTURE.md §5.2, §8.
#
# Paths below are container paths; infra/compose.yaml mounts them.

ui            = false
log_level     = "info"
log_format    = "json"
api_addr      = "https://openbao:8200"
cluster_addr  = "https://openbao:8201"
cluster_name  = "scanova"

# Integrated storage memory-maps its database, which mlock would pin whole.
# Swapping is prevented instead: Compose gives this container no swap.
disable_mlock = true

# Single node, integrated (Raft) storage on the openbao_data volume.
storage "raft" {
  path    = "/openbao/file"
  node_id = "scanova-1"
}

# TLS only. The certificate is issued by Scanova's internal CA.
listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_cert_file   = "/etc/scanova/tls/openbao.crt"
  tls_key_file    = "/etc/scanova/tls/openbao.key"
  tls_min_version = "tls13"
}

# Static auto-unseal. The key is a 32-byte file kept outside the data volume
# (infra/.state/openbao/seal/unseal.key on the host, see infra/README.md).
# current_key_id must change whenever the key itself changes.
seal "static" {
  current_key_id = "scanova-seal-1"
  current_key    = "file:///etc/scanova/seal/unseal.key"
}

# Every request to the vault is audited. Secrets in the entries are HMAC'd by
# OpenBao; the log goes to the container's stdout.
audit "file" "stdout" {
  description = "Audit log on standard output."
  options {
    file_path = "stdout"
  }
}
