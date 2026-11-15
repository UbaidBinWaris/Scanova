# Policy `scanova-api` — the API process (docs/ARCHITECTURE.md §8).
#
# May: encrypt, decrypt, re-wrap, generate data keys, sign, read key metadata.
# May not: create, rotate, reconfigure, export, back up, trim or delete a key.
# Anything not listed here is denied.
#
# Rendered by infra/scripts/bootstrap.sh; @NAME@ markers come from .env
# (BAO_TRANSIT_MOUNT and the four BAO_KEY_* names).

# ── User-type master keys (derived: every call carries the user id as context) ──
path "@TRANSIT@/encrypt/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/decrypt/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/rewrap/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/plaintext/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/wrapped/@KEY_UTK_ADMIN@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_UTK_ADMIN@" { capabilities = ["read"] }

path "@TRANSIT@/encrypt/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/decrypt/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/rewrap/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/plaintext/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/wrapped/@KEY_UTK_MEMBER@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_UTK_MEMBER@" { capabilities = ["read"] }

# ── System key ────────────────────────────────────────────────────────────────
path "@TRANSIT@/encrypt/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/decrypt/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/rewrap/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/plaintext/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/datakey/wrapped/@KEY_SYSTEM@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_SYSTEM@" { capabilities = ["read"] }

# ── Token-signing key (public keys are read from the key metadata) ────────────
path "@TRANSIT@/sign/@KEY_JWT@" { capabilities = ["update"] }
path "@TRANSIT@/keys/@KEY_JWT@" { capabilities = ["read"] }

# ── Own token only (the `default` policy is not attached) ─────────────────────
path "auth/token/lookup-self" { capabilities = ["read"] }
path "auth/token/renew-self" { capabilities = ["update"] }
path "auth/token/revoke-self" { capabilities = ["update"] }
path "sys/capabilities-self" { capabilities = ["update"] }
