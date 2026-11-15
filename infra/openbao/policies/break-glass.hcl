# Policy `scanova-break-glass` — the operator AppRole used by the infra scripts.
#
# OpenBao only accepts recovery key shares from an authenticated caller. This
# policy allows exactly that and nothing else: on its own it reads no secret
# and changes no configuration. A root token needs this credential AND a
# quorum of recovery key shares, and the scripts revoke it when they finish.

path "sys/generate-root-token/attempt" {
  capabilities = ["read", "create", "update", "delete"]
}

path "sys/generate-root-token/update" {
  capabilities = ["create", "update"]
}

path "auth/token/revoke-self" { capabilities = ["update"] }
