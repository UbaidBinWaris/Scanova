#!/usr/bin/env bash
# Shared helpers for the scripts in this directory. Sourced, never executed.
#
# Conventions the scripts rely on:
# - Configuration is read from and written to the ONE root .env.
# - Nothing secret is ever passed as a command-line argument: secrets travel
#   in the environment of a child process or on its standard input.
# - Everything that talks to a service does so from inside the Compose
#   network (`docker compose exec`), so the scripts work the same with and
#   without the development port mappings.

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
	echo "lib.sh is a library: source it from another script." >&2
	exit 2
fi

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPTS_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"

# A deployed release links .env and infra/.state to shared copies
# (infra/deploy/deploy.sh). Resolving the link here means env_set rewrites the
# shared file instead of replacing the link with a private copy.
ENV_FILE="$(readlink -f "$ROOT_DIR/.env" 2>/dev/null || printf '%s' "$ROOT_DIR/.env")"
ENV_TEMPLATE="$ROOT_DIR/.env.example"

STATE_DIR="$INFRA_DIR/.state"
MODE_FILE="$STATE_DIR/mode"
PKI_DIR="$STATE_DIR/pki"
BAO_STATE_DIR="$STATE_DIR/openbao"
SEAL_DIR="$BAO_STATE_DIR/seal"
SEAL_KEY_FILE="$SEAL_DIR/unseal.key"
RECOVERY_DIR="$BAO_STATE_DIR/recovery"
OPERATOR_FILE="$BAO_STATE_DIR/operator-approle.json"

# Fixed by infra/compose.yaml.
COMPOSE_PROJECT="scanova"
CA_IN_CONTAINER="/etc/scanova/pki/ca.crt"
PG_IN_NETWORK_HOST="postgres"
PG_IN_NETWORK_PORT="5432"

BAO_POLICY_API="scanova-api"
BAO_POLICY_WORKER="scanova-worker"
BAO_POLICY_BREAK_GLASS="scanova-break-glass"
BAO_ROLE_API="scanova-api"
BAO_ROLE_WORKER="scanova-worker"
BAO_ROLE_OPERATOR="scanova-operator"

S3_KEY_NAME="scanova-app"

# ── Output ────────────────────────────────────────────────────────────

if [[ -t 1 ]]; then
	C_BOLD=$'\033[1m' C_DIM=$'\033[2m' C_RED=$'\033[31m' C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RESET=$'\033[0m'
else
	C_BOLD="" C_DIM="" C_RED="" C_GREEN="" C_YELLOW="" C_RESET=""
fi

step() { printf '\n%s== %s%s\n' "$C_BOLD" "$*" "$C_RESET"; }
info() { printf '   %s\n' "$*"; }
ok() { printf '   %sok%s  %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '   %swarning%s  %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die() {
	printf '%serror%s  %s\n' "$C_RED" "$C_RESET" "$*" >&2
	exit 1
}

need() {
	local tool
	for tool in "$@"; do
		command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required but not installed."
	done
}

need_docker() {
	need docker
	docker compose version >/dev/null 2>&1 || die "Docker Compose v2 ('docker compose') is required."
	docker info >/dev/null 2>&1 || die "Cannot reach the Docker daemon. Is it running, and may this user use it?"
}

rand_hex() { openssl rand -hex "$1"; }

# ── The root .env ─────────────────────────────────────────────────────

# env_get KEY — value of KEY in .env, or nothing. Never sources the file.
env_get() {
	local line
	[[ -f "$ENV_FILE" ]] || return 0
	line="$(grep -E "^$1=" "$ENV_FILE" | tail -n 1 || true)"
	line="${line#*=}"
	if [[ "$line" == \"*\" || "$line" == \'*\' ]]; then
		line="${line:1:${#line}-2}"
	fi
	printf '%s' "$line"
}

# env_require KEY — like env_get, but a missing or empty value is fatal.
env_require() {
	local value
	value="$(env_get "$1")"
	[[ -n "$value" ]] || die "$1 is missing or empty in $ENV_FILE."
	printf '%s' "$value"
}

# env_set KEY VALUE — replaces the line in place (comments and order are
# kept), or appends it. The value is handed to awk through the environment.
env_set() {
	local tmp
	tmp="$(mktemp "$ENV_FILE.XXXXXX")"
	chmod 600 "$tmp"
	ENV_KEY="$1" ENV_VALUE="$2" awk '
		BEGIN { key = ENVIRON["ENV_KEY"]; value = ENVIRON["ENV_VALUE"]; done = 0 }
		index($0, key "=") == 1 { print key "=" value; done = 1; next }
		{ print }
		END { if (!done) print key "=" value }
	' "$ENV_FILE" >"$tmp"
	mv -f "$tmp" "$ENV_FILE"
}

# ── Compose ───────────────────────────────────────────────────────────

# "prod" (default) or "dev" (adds compose.dev.yaml: loopback ports for the
# data services). bootstrap.sh records the choice; the other scripts reuse it.
STACK_MODE=""

resolve_mode() {
	if [[ -n "${1:-}" ]]; then
		STACK_MODE="$1"
	elif [[ -f "$MODE_FILE" ]]; then
		STACK_MODE="$(<"$MODE_FILE")"
	else
		STACK_MODE="prod"
	fi
	[[ "$STACK_MODE" == "dev" || "$STACK_MODE" == "prod" ]] || die "Unknown stack mode '$STACK_MODE' (expected dev or prod)."
}

compose() {
	local files=(-f "$INFRA_DIR/compose.yaml")
	[[ -n "$STACK_MODE" ]] || resolve_mode ""
	if [[ "$STACK_MODE" == "dev" ]]; then
		files+=(-f "$INFRA_DIR/compose.dev.yaml")
	fi
	# Scanova's own Garage runs unless .env points at an external S3 service.
	if ! s3_external; then
		files+=(--profile bundled-s3)
	fi
	docker compose --env-file "$ENV_FILE" "${files[@]}" "$@"
}

# True when SCANOVA_S3_MODE=external: S3 is a service outside this stack.
s3_external() {
	[[ -f "$ENV_FILE" ]] && [[ "$(sed -n 's/^SCANOVA_S3_MODE=//p' "$ENV_FILE" | tail -n 1)" == "external" ]]
}

service_container() { compose ps -a -q "$1" 2>/dev/null | head -n 1; }

service_running() {
	local id
	id="$(service_container "$1")"
	[[ -n "$id" ]] && [[ "$(docker inspect -f '{{.State.Running}}' "$id" 2>/dev/null)" == "true" ]]
}

service_health() {
	local id
	id="$(service_container "$1")"
	[[ -n "$id" ]] || {
		echo "absent"
		return 0
	}
	docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo "absent"
}

# wait_healthy SERVICE [SECONDS]
wait_healthy() {
	local service="$1" timeout="${2:-120}" waited=0 status
	while :; do
		status="$(service_health "$service")"
		[[ "$status" == "healthy" ]] && return 0
		if ((waited >= timeout)); then
			compose logs --tail 30 "$service" >&2 || true
			die "Service '$service' is not healthy after ${timeout}s (status: $status)."
		fi
		sleep 2
		waited=$((waited + 2))
	done
}

# ── PostgreSQL ────────────────────────────────────────────────────────

# The main database is always served by this stack's `postgres` service. The
# key database is too, unless .env points KEYS_POSTGRES_HOST/PORT somewhere
# other than the main database's address; then it is "external" and the
# scripts reach it at the address in .env instead of provisioning it.
keys_db_location() {
	if [[ "$(env_get KEYS_POSTGRES_HOST)" == "$(env_get POSTGRES_HOST)" &&
		"$(env_get KEYS_POSTGRES_PORT)" == "$(env_get POSTGRES_PORT)" ]]; then
		echo "bundled"
	else
		echo "external"
	fi
}

# pg_super [psql args…] — superuser psql on the bundled instance, over the unix
# socket inside the container (peer authentication; no password involved).
pg_super() {
	compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U postgres "$@"
}

# pg_as TARGET SSLMODE DATABASE USER [psql args…] — psql as a role over TCP,
# with the password in PGPASSWORD. TARGET is "main" or "keys". A bundled
# database is reached from inside the Compose network; an external key
# database from the host's network, at the address in .env.
pg_as() {
	local target="$1" sslmode="$2" database="$3" user="$4"
	shift 4
	local host="$PG_IN_NETWORK_HOST" port="$PG_IN_NETWORK_PORT" conn
	if [[ "$target" == "keys" && "$(keys_db_location)" == "external" ]]; then
		host="$(env_require KEYS_POSTGRES_HOST)"
		port="$(env_require KEYS_POSTGRES_PORT)"
		conn="host=$host port=$port dbname=$database user=$user sslmode=$sslmode sslrootcert=$CA_IN_CONTAINER"
		docker run --rm -i --network host -e PGPASSWORD -e PGCONNECT_TIMEOUT=10 \
			-v "$PKI_DIR/ca.crt:$CA_IN_CONTAINER:ro" "$(service_image postgres)" \
			psql -X -q -A -t -v ON_ERROR_STOP=1 "$conn" "$@"
		return
	fi
	conn="host=$host port=$port dbname=$database user=$user sslmode=$sslmode sslrootcert=$CA_IN_CONTAINER"
	compose exec -T -e PGPASSWORD -e PGCONNECT_TIMEOUT=10 postgres \
		psql -X -q -A -t -v ON_ERROR_STOP=1 "$conn" "$@"
}

service_image() { compose config --format json | jq -r --arg s "$1" '.services[$s].image'; }

# provision_database DB MIGRATOR MIGRATOR_PASSWORD APP APP_PASSWORD — creates
# or reconciles one database, its two roles and its connect isolation on the
# bundled instance. Passwords reach psql through the environment.
provision_database() {
	SCANOVA_PG_DB="$1" SCANOVA_PG_MIGRATOR="$2" SCANOVA_PG_MIGRATOR_PASSWORD="$3" \
		SCANOVA_PG_APP="$4" SCANOVA_PG_APP_PASSWORD="$5" \
		compose exec -T \
		-e SCANOVA_PG_DB -e SCANOVA_PG_MIGRATOR -e SCANOVA_PG_MIGRATOR_PASSWORD \
		-e SCANOVA_PG_APP -e SCANOVA_PG_APP_PASSWORD \
		postgres psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres -f - \
		<"$INFRA_DIR/postgres/provision-database.sql"
}

# ── Garage ────────────────────────────────────────────────────────────

garage_cli() { compose exec -T -e RUST_LOG=error garage /garage "$@"; }

# garage_api ENDPOINT [JSON] — admin API call, JSON result on stdout.
garage_api() {
	if [[ $# -ge 2 ]]; then
		garage_cli json-api "$1" "$2"
	else
		garage_cli json-api "$1"
	fi
}

# ── OpenBao ───────────────────────────────────────────────────────────

# bao_cli [args…] — the `bao` CLI inside the openbao container. The token is
# taken from BAO_TOKEN in the caller's environment:
#   BAO_TOKEN="$token" bao_cli read …      authenticated
#   BAO_TOKEN="" bao_cli status            unauthenticated
bao_cli() {
	compose exec -T -e BAO_TOKEN openbao bao "$@"
}

# JSON status even when sealed or uninitialised (`bao status` exits 2 then).
bao_status_json() { BAO_TOKEN="" bao_cli status -format=json 2>/dev/null || true; }

bao_wait_api() {
	local timeout="${1:-90}" waited=0
	until [[ "$(bao_status_json | jq -r '.initialized | tostring' 2>/dev/null)" =~ ^(true|false)$ ]]; do
		if ((waited >= timeout)); then
			compose logs --tail 30 openbao >&2 || true
			die "OpenBao did not answer within ${timeout}s."
		fi
		sleep 2
		waited=$((waited + 2))
	done
}

# bao_approle_login ROLE_ID SECRET_ID — prints a client token, or fails.
bao_approle_login() {
	ROLE_ID="$1" SECRET_ID="$2" jq -n '{role_id: env.ROLE_ID, secret_id: env.SECRET_ID}' |
		BAO_TOKEN="" bao_cli write -field=token auth/approle/login - 2>/dev/null
}

bao_revoke_self() { BAO_TOKEN="$1" bao_cli token revoke -self >/dev/null 2>&1 || true; }

# Recovery key shares available on this host, one per line.
recovery_shares() {
	local dir="${SCANOVA_RECOVERY_DIR:-$RECOVERY_DIR}" file
	[[ -d "$dir" ]] || return 0
	for file in "$dir"/share-*.key; do
		[[ -s "$file" ]] && tr -d '[:space:]' <"$file" && echo
	done
	return 0
}

# A root token exists only between bao_root_open and bao_root_close, only in
# this process's memory. It is made from the operator AppRole (which can do
# nothing else) plus a quorum of recovery key shares, and revoked on close.
BAO_ROOT_TOKEN=""

bao_root_available() {
	[[ -s "$OPERATOR_FILE" ]] && [[ -n "$(recovery_shares)" ]]
}

bao_root_open() {
	local operator_token init nonce otp share out encoded=""
	[[ -s "$OPERATOR_FILE" ]] || die "$OPERATOR_FILE is missing: cannot request a root token."
	operator_token="$(bao_approle_login "$(jq -r .role_id "$OPERATOR_FILE")" "$(jq -r .secret_id "$OPERATOR_FILE")")" ||
		die "The operator AppRole in $OPERATOR_FILE was refused by OpenBao."

	BAO_TOKEN="$operator_token" bao_cli operator generate-root -cancel >/dev/null 2>&1 || true
	init="$(BAO_TOKEN="$operator_token" bao_cli operator generate-root -init -format=json)"
	nonce="$(jq -r .nonce <<<"$init")"
	otp="$(jq -r .otp <<<"$init")"

	while IFS= read -r share; do
		[[ -n "$share" ]] || continue
		out="$(printf '%s' "$share" | BAO_TOKEN="$operator_token" bao_cli operator generate-root -nonce="$nonce" -format=json -)" || {
			BAO_TOKEN="$operator_token" bao_cli operator generate-root -cancel >/dev/null 2>&1 || true
			bao_revoke_self "$operator_token"
			die "OpenBao rejected a recovery key share."
		}
		if [[ "$(jq -r .complete <<<"$out")" == "true" ]]; then
			encoded="$(jq -r .encoded_token <<<"$out")"
			break
		fi
	done < <(recovery_shares)

	if [[ -z "$encoded" ]]; then
		BAO_TOKEN="$operator_token" bao_cli operator generate-root -cancel >/dev/null 2>&1 || true
		bao_revoke_self "$operator_token"
		die "Not enough recovery key shares in ${SCANOVA_RECOVERY_DIR:-$RECOVERY_DIR} to reach the threshold."
	fi

	BAO_ROOT_TOKEN="$(ENCODED="$encoded" OTP="$otp" jq -n '{encoded_token: env.ENCODED, otp: env.OTP}' |
		BAO_TOKEN="" bao_cli write -field=token sys/decode-token -)"
	bao_revoke_self "$operator_token"
	[[ -n "$BAO_ROOT_TOKEN" ]] || die "Could not decode the generated root token."
}

bao_root_close() {
	if [[ -n "$BAO_ROOT_TOKEN" ]]; then
		bao_revoke_self "$BAO_ROOT_TOKEN"
		BAO_ROOT_TOKEN=""
	fi
}

# bao_root [args…] — `bao` with the root token of the open session.
bao_root() {
	[[ -n "$BAO_ROOT_TOKEN" ]] || die "No root session is open."
	BAO_TOKEN="$BAO_ROOT_TOKEN" bao_cli "$@"
}

# bao_new_secret_id ROLE — prints a fresh secret-id for an AppRole.
bao_new_secret_id() {
	bao_root write -f -field=secret_id "auth/approle/role/$1/secret-id"
}

# ── PKI ───────────────────────────────────────────────────────────────

# SHA-256 of the CA certificate in DER form, lowercase hex. This is the
# fingerprint the phone pins during pairing.
ca_fingerprint() {
	openssl x509 -in "$PKI_DIR/ca.crt" -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1
}
