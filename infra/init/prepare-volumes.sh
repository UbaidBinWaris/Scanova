#!/bin/sh
# One-shot `init` service (infra/compose.yaml). Runs before the long-lived
# services, every time the stack is brought up.
#
# The services run as fixed non-root users that cannot read the operator-owned
# 0600 files under infra/.state, and PostgreSQL refuses a private key it does
# not own. So this script, the only container that runs as root, does three
# things and exits:
#   1. gives each data volume to the user of the service that writes to it;
#   2. copies each service's own TLS key pair into a volume only that service
#      mounts, owned by its user, key mode 0400;
#   3. makes OpenBao's unseal key readable by OpenBao's group, in place. That
#      key is never copied anywhere: there is one copy, the host file.
# It has no network, a read-only root filesystem and only the capabilities
# needed to read, chown and chmod those files.

set -eu

fail() {
	echo "init: $*" >&2
	exit 1
}

# own_dir <uid:gid> <dir> — top level only; the service owns what it creates.
own_dir() {
	chown "$1" "$2"
	chmod 0700 "$2"
}

# install_file <uid:gid> <mode> <source> <target> — atomic, skipped when the
# content is already identical so re-runs change nothing.
install_file() {
	[ -f "$3" ] || fail "$3 is missing. Run infra/scripts/bootstrap.sh first."
	if [ -f "$4" ] && cmp -s "$3" "$4"; then
		chown "$1" "$4"
		chmod "$2" "$4"
		return 0
	fi
	cp "$3" "$4.tmp"
	chown "$1" "$4.tmp"
	chmod "$2" "$4.tmp"
	mv -f "$4.tmp" "$4"
}

# group_readable <gid> <mode> <path> — sets group and mode only when they
# differ, so a correctly prepared (even read-only) medium is left untouched.
group_readable() {
	if [ "$(stat -c '%g %a' "$3")" != "$1 $2" ]; then
		chgrp "$1" "$3" && chmod "$2" "$3" ||
			fail "cannot make $3 readable by group $1. On a filesystem without POSIX permissions, mount it with gid=$1 and a matching mask."
	fi
}

: "${EDGE_ID:?}" "${POSTGRES_ID:?}" "${OPENBAO_ID:?}" "${GARAGE_ID:?}"

# ── edge (Caddy) ──────────────────────────────────────────────────────
own_dir "$EDGE_ID" /out/edge-data
own_dir "$EDGE_ID" /out/edge-tls
install_file "$EDGE_ID" 0444 /in/pki/edge.crt /out/edge-tls/edge.crt
install_file "$EDGE_ID" 0400 /in/pki/edge.key /out/edge-tls/edge.key

# ── postgres ──────────────────────────────────────────────────────────
own_dir "$POSTGRES_ID" /out/postgres-data
own_dir "$POSTGRES_ID" /out/postgres-tls
install_file "$POSTGRES_ID" 0444 /in/pki/postgres.crt /out/postgres-tls/postgres.crt
install_file "$POSTGRES_ID" 0400 /in/pki/postgres.key /out/postgres-tls/postgres.key

# ── openbao ───────────────────────────────────────────────────────────
own_dir "$OPENBAO_ID" /out/openbao-data
own_dir "$OPENBAO_ID" /out/openbao-tls
install_file "$OPENBAO_ID" 0444 /in/pki/openbao.crt /out/openbao-tls/openbao.crt
install_file "$OPENBAO_ID" 0400 /in/pki/openbao.key /out/openbao-tls/openbao.key

# The unseal key stays where it is (the host directory behind /in/seal, which
# OpenBao mounts read-only). Its owner keeps it; only OpenBao's group gains
# read access. The directory above it on the host stays closed to everyone
# but the owner.
[ -f /in/seal/unseal.key ] || fail "/in/seal/unseal.key is missing. Run infra/scripts/bootstrap.sh first, or mount the medium that holds the unseal key."
group_readable "${OPENBAO_ID#*:}" 750 /in/seal
group_readable "${OPENBAO_ID#*:}" 640 /in/seal/unseal.key

# ── garage ────────────────────────────────────────────────────────────
own_dir "$GARAGE_ID" /out/garage-meta
own_dir "$GARAGE_ID" /out/garage-data

# The AI volumes (profile ai) are prepared by infra/init/prepare-ai.sh.

echo "init: volumes and service secrets are in place"
