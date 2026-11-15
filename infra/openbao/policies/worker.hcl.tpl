# Policy `scanova-worker` — the background worker (docs/ARCHITECTURE.md §8).
#
# Everything `scanova-api` may do, plus: rotate a key and raise its minimum
# decryption/encryption version. The `config` endpoint could also make a key
# exportable or deletable, so only the two version parameters are allowed.
# May not: create, export, back up, trim or delete a key.
#
# Rendered by infra/scripts/bootstrap.sh; @NAME@ markers come from .env.

# ── User-type master keys ─────────────────────────────────────────────────────
path "@TRANSIT@/encrypt/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/decrypt/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/rewrap/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/plaintext/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/wrapped/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_UTK_ADMIN@" { capabilities = ["read"] }
path "@TRANSIT@/keys/@KEY_UTK_ADMIN@/rotate" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_UTK_ADMIN@/config" {
  capabilities = ["update"]
  allowed_parameters = {
    "min_decryption_version" = []
    "min_encryption_version" = []
  }
}

path "@TRANSIT@/encrypt/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/decrypt/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/rewrap/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/plaintext/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/wrapped/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_UTK_MEMBER@" { capabilities = ["read"] }
path "@TRANSIT@/keys/@KEY_UTK_MEMBER@/rotate" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_UTK_MEMBER@/config" {
  capabilities = ["update"]
  allowed_parameters = {
    "min_decryption_version" = []
    "min_encryption_version" = []
  }
}

# ── System key ────────────────────────────────────────────────────────────────
path "@TRANSIT@/encrypt/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/decrypt/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/rewrap/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/plaintext/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/wrapped/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_SYSTEM@" { capabilities = ["read"] }
path "@TRANSIT@/keys/@KEY_SYSTEM@/rotate" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_SYSTEM@/config" {
  capabilities = ["update"]
  allowed_parameters = {
    "min_decryption_version" = []
    "min_encryption_version" = []
  }
}

# ── Token-signing key ─────────────────────────────────────────────────────────
path "@TRANSIT@/sign/@KEY_JWT@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_JWT@" { capabilities = ["read"] }
path "@TRANSIT@/keys/@KEY_JWT@/rotate" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_JWT@/config" {
  capabilities = ["update"]
  allowed_parameters = {
    "min_decryption_version" = []
    "min_encryption_version" = []
  }
}

# ── Own token only (the `default` policy is not attached) ─────────────────────
path "auth/token/lookup-self" { capabilities = ["read"] }
path "auth/token/renew-self" { capabilities = ["update"] }
path "auth/token/revoke-self" { capabilities = ["update"] }
path "sys/capabilities-self" { capabilities = ["update"] }
