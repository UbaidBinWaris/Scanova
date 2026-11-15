#!/usr/bin/env bash
# Scanova bootstrap — takes a fresh clone to a running, configured stack
# (docs/ARCHITECTURE.md §8). Idempotent: run it again at any time; it creates
# what is missing, renews what is about to expire and changes nothing else.
#
#   1. .env from .env.example, every empty secret generated
#   2. internal CA and certificates for edge, postgres and openbao
#   3. data services up; both databases, the four roles, connect isolation
#   4. OpenBao: init, Transit mount and keys, policies, AppRoles
#   5. Garage: bucket and access key
#   6. pairing details for the phone

set -Eeuo pipefail
umask 077

# shellcheck source=infra/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

CA_DAYS=3650         # internal CA lifetime
LEAF_DAYS=825        # service certificate lifetime
LEAF_RENEW_DAYS=60   # re-issue a certificate that expires sooner than this
EDGE_DEFAULT_SNI="edge.scanova.internal" # must match infra/caddy/tls/internal.global

MODE_FLAG=""
PUBLIC_HOST=""
RECOVERY_SHARES=3
RECOVERY_THRESHOLD=2
SHOW_QR=1
ENV_CREATED=0
CHANGES=()
RESTART_SERVICES=()

usage() {
	cat <<'EOF'
Usage: infra/scripts/bootstrap.sh [options]

  --dev                    Also publish PostgreSQL, Garage and OpenBao on
                           127.0.0.1 (infra/compose.dev.yaml). Remembered.
  --prod                   Publish nothing but the edge (default). Remembered.
  --public-host HOST       Host name or IP the phone uses to reach this
                           server; written to SCANOVA_PUBLIC_URL/_HOSTS.
                           Default on first run: this machine's LAN address.
  --recovery-shares N      OpenBao recovery key shares to create (default 3).
  --recovery-threshold N   Shares needed for a root token (default 2).
  --recovery-dir DIR       Read recovery key shares from DIR instead of
                           infra/.state/openbao/recovery (for later runs,
                           after the shares were moved off this server).
  --no-qr                  Print the pairing link as text only.
  -h, --help               Show this help.
EOF
}

changed() { CHANGES+=("$1"); }

while [[ $# -gt 0 ]]; do
	case "$1" in
	--dev) MODE_FLAG="dev" ;;
	--prod) MODE_FLAG="prod" ;;
	--public-host)
		PUBLIC_HOST="${2:?--public-host needs a value}"
		shift
		;;
	--recovery-shares)
		RECOVERY_SHARES="${2:?--recovery-shares needs a value}"
		shift
		;;
	--recovery-threshold)
		RECOVERY_THRESHOLD="${2:?--recovery-threshold needs a value}"
		shift
		;;
	--recovery-dir)
		export SCANOVA_RECOVERY_DIR="${2:?--recovery-dir needs a value}"
		shift
		;;
	--no-qr) SHOW_QR=0 ;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		usage >&2
		die "Unknown option: $1"
		;;
	esac
	shift
done

# A root token must not outlive this script, whatever happens.
trap 'bao_root_close' EXIT

# ── Step 1: .env ──────────────────────────────────────────────────────

detect_lan_host() {
	command -v ip >/dev/null 2>&1 || return 0
	ip -4 -o route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n 1
}

set_public_host() {
	local host="$1" port url_host
	port="$(env_require SCANOVA_HTTPS_PORT)"
	url_host="$host"
	[[ "$host" == *:* ]] && url_host="[$host]"
	env_set SCANOVA_PUBLIC_URL "https://$url_host:$port"
	env_set SCANOVA_PUBLIC_HOSTS "$host,localhost,127.0.0.1,10.0.2.2"
	changed "public address set to https://$url_host:$port"
}

generate_secret() {
	if [[ -z "$(env_get "$1")" ]]; then
		env_set "$1" "$(rand_hex "$2")"
		changed "generated $1"
	fi
}

check_name() { # KEY PATTERN DESCRIPTION
	[[ "$(env_require "$1")" =~ $2 ]] || die "$1 in .env must be $3."
}

step_env() {
	step "1/6  Environment file"
	[[ -f "$ENV_TEMPLATE" ]] || die "$ENV_TEMPLATE not found."

	if [[ ! -f "$ENV_FILE" ]]; then
		cp "$ENV_TEMPLATE" "$ENV_FILE"
		ENV_CREATED=1
		changed "created .env from .env.example"
	fi
	chmod 600 "$ENV_FILE"

	# Variables a newer .env.example introduced are added with their defaults.
	local line key added=0
	while IFS= read -r line; do
		key="${line%%=*}"
		if ! grep -q "^${key}=" "$ENV_FILE"; then
			if ((added == 0)); then
				printf '\n# ── Added by bootstrap.sh from .env.example ──\n' >>"$ENV_FILE"
			fi
			printf '%s\n' "$line" >>"$ENV_FILE"
			added=$((added + 1))
			changed "added $key to .env"
		fi
	done < <(grep -E '^[A-Z][A-Z0-9_]*=' "$ENV_TEMPLATE")

	if [[ -n "$PUBLIC_HOST" ]]; then
		set_public_host "$PUBLIC_HOST"
	elif ((ENV_CREATED)); then
		local detected
		detected="$(detect_lan_host)"
		if [[ -n "$detected" ]]; then
			set_public_host "$detected"
		else
			warn "Could not detect this machine's LAN address; SCANOVA_PUBLIC_URL keeps the template value. Re-run with --public-host."
		fi
	fi

	generate_secret WEB_SERVICE_TOKEN 32
	generate_secret POSTGRES_SUPERUSER_PASSWORD 24
	generate_secret POSTGRES_APP_PASSWORD 24
	generate_secret POSTGRES_MIGRATOR_PASSWORD 24
	generate_secret KEYS_POSTGRES_APP_PASSWORD 24
	generate_secret KEYS_POSTGRES_MIGRATOR_PASSWORD 24
	generate_secret GARAGE_RPC_SECRET 32 # Garage requires exactly 32 bytes, hex
	generate_secret GARAGE_ADMIN_TOKEN 32

	# Values the scripts and config templates depend on.
	local tls_mode
	tls_mode="$(env_require SCANOVA_TLS_MODE)"
	[[ "$tls_mode" == "internal" || "$tls_mode" == "acme" || "$tls_mode" == "public" ]] || die "SCANOVA_TLS_MODE must be 'internal', 'acme' or 'public'."
	if [[ "$tls_mode" == "public" ]]; then
		[[ -n "$(env_get SCANOVA_SITE_HOST)" && -n "$(env_get SCANOVA_ADMIN_HOST)" ]] ||
			die "SCANOVA_TLS_MODE=public needs SCANOVA_SITE_HOST and SCANOVA_ADMIN_HOST in .env."
	fi
	[[ "$(env_require SCANOVA_PUBLIC_URL)" == https://* ]] || die "SCANOVA_PUBLIC_URL must start with https://."
	if [[ "$tls_mode" == "acme" ]]; then
		env_require SCANOVA_ACME_EMAIL >/dev/null
	fi
	check_name SCANOVA_HTTPS_PORT '^[0-9]+$' "a port number"
	check_name SCANOVA_TMP_DIR '^/' "an absolute path"
	check_name KEY_ROTATION_UTK_DAYS '^[1-9][0-9]*$' "a positive number of days"
	check_name KEY_ROTATION_JWT_DAYS '^[1-9][0-9]*$' "a positive number of days"

	local name
	for name in BAO_TRANSIT_MOUNT BAO_KEY_UTK_ADMIN BAO_KEY_UTK_MEMBER BAO_KEY_SYSTEM BAO_KEY_JWT S3_BUCKET; do
		check_name "$name" '^[A-Za-z0-9][A-Za-z0-9_.-]*$' "letters, digits, '.', '_' or '-'"
	done
	for name in POSTGRES_DB POSTGRES_APP_USER POSTGRES_MIGRATOR_USER \
		KEYS_POSTGRES_DB KEYS_POSTGRES_APP_USER KEYS_POSTGRES_MIGRATOR_USER; do
		check_name "$name" '^[a-z_][a-z0-9_]*$' "a lowercase PostgreSQL identifier"
	done
	local roles
	roles="$(printf '%s\n' "$(env_get POSTGRES_APP_USER)" "$(env_get POSTGRES_MIGRATOR_USER)" \
		"$(env_get KEYS_POSTGRES_APP_USER)" "$(env_get KEYS_POSTGRES_MIGRATOR_USER)" | sort -u | wc -l)"
	[[ "$roles" -eq 4 ]] || die "The four PostgreSQL role names in .env must all be different."
	[[ "$(env_get POSTGRES_DB)" != "$(env_get KEYS_POSTGRES_DB)" ]] || die "POSTGRES_DB and KEYS_POSTGRES_DB must be different databases."

	if ! s3_external; then
	local garage_region
	garage_region="$(sed -n 's/^s3_region *= *"\(.*\)"/\1/p' "$INFRA_DIR/garage/garage.toml")"
	[[ "$(env_require S3_REGION)" == "$garage_region" ]] ||
		die "S3_REGION in .env must be '$garage_region' (s3_region in infra/garage/garage.toml)."
	fi

	ok ".env is complete ($ENV_FILE, mode 0600)"
}

# ── Step 2: internal CA and certificates ──────────────────────────────

leaf_valid() { # NAME SANS
	local name="$1" sans="$2" crt="$PKI_DIR/$1.crt" key="$PKI_DIR/$1.key"
	[[ -s "$crt" && -s "$key" && -f "$PKI_DIR/$name.sans" ]] || return 1
	[[ "$(<"$PKI_DIR/$name.sans")" == "$sans" ]] || return 1
	openssl verify -CAfile "$PKI_DIR/ca.crt" "$crt" >/dev/null 2>&1 || return 1
	openssl x509 -in "$crt" -noout -checkend "$((LEAF_RENEW_DAYS * 86400))" >/dev/null 2>&1 || return 1
	[[ "$(openssl x509 -in "$crt" -noout -pubkey | openssl dgst -sha256)" == \
	"$(openssl pkey -in "$key" -pubout | openssl dgst -sha256)" ]] || return 1
}

issue_leaf() { # NAME SANS SERVICE
	local name="$1" sans="$2" service="$3"
	if leaf_valid "$name" "$sans"; then
		return 0
	fi
	[[ -s "$PKI_DIR/ca.key" ]] ||
		die "The $name certificate must be (re)issued but $PKI_DIR/ca.key is not on this host. Put the CA key back and re-run."

	local tmp
	tmp="$(mktemp -d "$PKI_DIR/.issue.XXXXXX")"
	openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/key" 2>/dev/null
	openssl req -new -key "$tmp/key" -subj "/O=Scanova/CN=$name" -out "$tmp/csr"
	cat >"$tmp/ext" <<EOF
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
subjectAltName = $sans
EOF
	openssl x509 -req -in "$tmp/csr" -CA "$PKI_DIR/ca.crt" -CAkey "$PKI_DIR/ca.key" \
		-set_serial "0x$(rand_hex 16)" -days "$LEAF_DAYS" -sha256 -extfile "$tmp/ext" -out "$tmp/crt" 2>/dev/null
	mv -f "$tmp/key" "$PKI_DIR/$name.key"
	mv -f "$tmp/crt" "$PKI_DIR/$name.crt"
	printf '%s' "$sans" >"$PKI_DIR/$name.sans"
	rm -rf "$tmp"
	chmod 600 "$PKI_DIR/$name.key"
	chmod 644 "$PKI_DIR/$name.crt"
	changed "issued the $name certificate ($LEAF_DAYS days)"
	RESTART_SERVICES+=("$service")
}

# Turns "a.example,192.168.1.5,::1" into "DNS:a.example,IP:192.168.1.5,IP:::1".
sans_from_hosts() {
	local host out=() list
	IFS=',' read -r -a list <<<"$1"
	for host in "${list[@]}"; do
		host="${host//[[:space:]]/}"
		[[ -n "$host" ]] || continue
		if [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ || "$host" == *:* ]]; then
			out+=("IP:$host")
		else
			out+=("DNS:$host")
		fi
	done
	local IFS=','
	printf '%s' "${out[*]}"
}

step_pki() {
	step "2/6  Internal CA and certificates"
	mkdir -p "$STATE_DIR" "$PKI_DIR"
	chmod 700 "$STATE_DIR" "$PKI_DIR"

	if [[ ! -s "$PKI_DIR/ca.crt" ]]; then
		openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$PKI_DIR/ca.key" 2>/dev/null
		openssl req -x509 -new -key "$PKI_DIR/ca.key" -sha256 -days "$CA_DAYS" \
			-subj "/O=Scanova/CN=Scanova Internal CA $(rand_hex 4)" \
			-addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
			-addext "keyUsage=critical,keyCertSign,cRLSign" \
			-out "$PKI_DIR/ca.crt"
		chmod 600 "$PKI_DIR/ca.key"
		chmod 644 "$PKI_DIR/ca.crt"
		changed "created the internal CA ($CA_DAYS days)"
	fi
	openssl x509 -in "$PKI_DIR/ca.crt" -noout -checkend $((LEAF_RENEW_DAYS * 86400)) >/dev/null ||
		warn "The internal CA expires within $LEAF_RENEW_DAYS days. Replacing it means pairing every phone again."

	local edge_sans
	edge_sans="$(sans_from_hosts "$(env_require SCANOVA_PUBLIC_HOSTS)")"
	[[ -n "$edge_sans" ]] || die "SCANOVA_PUBLIC_HOSTS in .env lists no host."
	issue_leaf edge "DNS:$EDGE_DEFAULT_SNI,$edge_sans" edge
	issue_leaf postgres "DNS:postgres,DNS:localhost,IP:127.0.0.1" postgres
	issue_leaf openbao "DNS:openbao,DNS:localhost,IP:127.0.0.1" openbao

	ok "CA and certificates are valid ($PKI_DIR)"
}

# ── OpenBao unseal key (before the first start) ───────────────────────

step_seal_key() {
	mkdir -p "$BAO_STATE_DIR"
	chmod 700 "$BAO_STATE_DIR"
	# Created 0700/0600. The `init` service later opens the directory and the
	# key to OpenBao's group (0750/0640), so their modes are not reset here.
	[[ -d "$SEAL_DIR" ]] || mkdir "$SEAL_DIR"
	if [[ ! -s "$SEAL_KEY_FILE" ]]; then
		if docker volume inspect "${COMPOSE_PROJECT}_openbao_data" >/dev/null 2>&1; then
			die "OpenBao already has data but its unseal key ($SEAL_KEY_FILE) is missing. Restore the key from your backup. Without it the vault, and with it every document, is unrecoverable."
		fi
		openssl rand -out "$SEAL_KEY_FILE" 32
		chmod 600 "$SEAL_KEY_FILE"
		changed "generated the OpenBao unseal key"
	fi
}

# ── Step 3: data services, databases and roles ────────────────────────

step_services() {
	step "3/6  Data services, databases and roles"
	local service previously_running=()
	for service in edge postgres openbao; do
		if service_running "$service"; then
			previously_running+=("$service")
		fi
	done

	compose up -d --quiet-pull

	# A certificate issued in this run reaches a running service on restart.
	for service in "${RESTART_SERVICES[@]}"; do
		if [[ " ${previously_running[*]} " == *" $service "* ]]; then
			compose restart "$service"
			changed "restarted $service to load its new certificate"
		fi
	done

	wait_healthy postgres 180
	s3_external || wait_healthy garage 120

	# The bundled instance: nobody but the superuser enters the maintenance
	# databases, so a role sees only the database it was provisioned for.
	pg_super -d postgres <<'SQL'
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
SQL

	provision_database "$(env_require POSTGRES_DB)" \
		"$(env_require POSTGRES_MIGRATOR_USER)" "$(env_require POSTGRES_MIGRATOR_PASSWORD)" \
		"$(env_require POSTGRES_APP_USER)" "$(env_require POSTGRES_APP_PASSWORD)"
	ok "main database '$(env_get POSTGRES_DB)': roles $(env_get POSTGRES_MIGRATOR_USER), $(env_get POSTGRES_APP_USER)"

	if [[ "$(keys_db_location)" == "bundled" ]]; then
		provision_database "$(env_require KEYS_POSTGRES_DB)" \
			"$(env_require KEYS_POSTGRES_MIGRATOR_USER)" "$(env_require KEYS_POSTGRES_MIGRATOR_PASSWORD)" \
			"$(env_require KEYS_POSTGRES_APP_USER)" "$(env_require KEYS_POSTGRES_APP_PASSWORD)"
		ok "key database '$(env_get KEYS_POSTGRES_DB)': roles $(env_get KEYS_POSTGRES_MIGRATOR_USER), $(env_get KEYS_POSTGRES_APP_USER)"
	else
		warn "The key database is on another instance ($(env_get KEYS_POSTGRES_HOST):$(env_get KEYS_POSTGRES_PORT)) and was not provisioned. Run infra/postgres/provision-database.sql there, and point KEYS_POSTGRES_HOST/PORT in infra/compose.yaml at it."
	fi
}

# ── Step 4: OpenBao ───────────────────────────────────────────────────

render_policy() { # TEMPLATE
	sed -e "s|@TRANSIT@|$(env_require BAO_TRANSIT_MOUNT)|g" \
		-e "s|@KEY_UTK_ADMIN@|$(env_require BAO_KEY_UTK_ADMIN)|g" \
		-e "s|@KEY_UTK_MEMBER@|$(env_require BAO_KEY_UTK_MEMBER)|g" \
		-e "s|@KEY_SYSTEM@|$(env_require BAO_KEY_SYSTEM)|g" \
		-e "s|@KEY_JWT@|$(env_require BAO_KEY_JWT)|g" "$1"
}

ensure_policy() { # NAME CONTENT
	local current
	current="$(bao_root policy read "$1" 2>/dev/null || true)"
	if [[ "$current" != "$2" ]]; then
		printf '%s\n' "$2" | bao_root policy write "$1" - >/dev/null
		changed "wrote OpenBao policy $1"
	fi
}

# ensure_transit_key NAME TYPE DERIVED AUTO_ROTATE_HOURS (0 = never)
ensure_transit_key() {
	local name="$1" type="$2" derived="$3" hours="$4" mount path meta
	mount="$(env_require BAO_TRANSIT_MOUNT)"
	path="$mount/keys/$name"
	if ! meta="$(bao_root read -format=json "$path" 2>/dev/null)"; then
		bao_root write -f "$path" "type=$type" "derived=$derived" exportable=false \
			allow_plaintext_backup=false "auto_rotate_period=${hours}h" >/dev/null
		changed "created Transit key $name ($type)"
		meta="$(bao_root read -format=json "$path")"
	fi

	# These cannot be changed after creation, so a mismatch is fatal.
	jq -e --arg type "$type" --argjson derived "$derived" '
		.data.type == $type and .data.derived == $derived
		and .data.exportable == false and .data.allow_plaintext_backup == false' <<<"$meta" >/dev/null ||
		die "Transit key $name exists with unexpected properties (type/derived/exportable/backup). It cannot be changed in place."

	if ! jq -e --argjson seconds "$((hours * 3600))" '
		.data.deletion_allowed == false and .data.auto_rotate_period == $seconds' <<<"$meta" >/dev/null; then
		bao_root write "$path/config" deletion_allowed=false "auto_rotate_period=${hours}h" >/dev/null
		changed "set $name: deletion not allowed, auto-rotate every ${hours}h"
	fi
}

ensure_approle() { # ROLE POLICY TOKEN_TTL TOKEN_MAX_TTL
	bao_root write "auth/approle/role/$1" "token_policies=$2" token_no_default_policy=true \
		"token_ttl=$3" "token_max_ttl=$4" token_type=service bind_secret_id=true \
		secret_id_ttl=0 secret_id_num_uses=0 token_num_uses=0 >/dev/null
}

# Keeps the AppRole credentials in .env when they still log in; otherwise
# stores the current role-id and a new secret-id.
ensure_approle_env() { # ROLE ROLE_ID_VAR SECRET_ID_VAR
	local role="$1" role_var="$2" secret_var="$3" role_id token
	role_id="$(bao_root read -field=role_id "auth/approle/role/$role/role-id")"
	if [[ "$(env_get "$role_var")" != "$role_id" ]]; then
		env_set "$role_var" "$role_id"
		changed "wrote $role_var to .env"
	fi
	if [[ -n "$(env_get "$secret_var")" ]] && token="$(bao_approle_login "$role_id" "$(env_get "$secret_var")")"; then
		bao_revoke_self "$token"
		return 0
	fi
	env_set "$secret_var" "$(bao_new_secret_id "$role")"
	changed "issued a new secret-id for AppRole $role ($secret_var)"
}

ensure_operator_approle() {
	local role_id token
	ensure_approle "$BAO_ROLE_OPERATOR" "$BAO_POLICY_BREAK_GLASS" 10m 10m
	role_id="$(bao_root read -field=role_id "auth/approle/role/$BAO_ROLE_OPERATOR/role-id")"
	if [[ -s "$OPERATOR_FILE" && "$(jq -r .role_id "$OPERATOR_FILE")" == "$role_id" ]] &&
		token="$(bao_approle_login "$role_id" "$(jq -r .secret_id "$OPERATOR_FILE")")"; then
		bao_revoke_self "$token"
		return 0
	fi
	ROLE_ID="$role_id" SECRET_ID="$(bao_new_secret_id "$BAO_ROLE_OPERATOR")" \
		jq -n '{role_id: env.ROLE_ID, secret_id: env.SECRET_ID}' >"$OPERATOR_FILE"
	chmod 600 "$OPERATOR_FILE"
	changed "stored the operator AppRole in $OPERATOR_FILE"
}

# Revokes every root token except the one in use. There should be none; this
# clears one left behind by a run that was killed mid-way.
revoke_stray_root_tokens() {
	local own accessor
	own="$(bao_root token lookup -format=json | jq -r .data.accessor)"
	while IFS= read -r accessor; do
		[[ -n "$accessor" && "$accessor" != "$own" ]] || continue
		if bao_root token lookup -format=json -accessor "$accessor" 2>/dev/null |
			jq -e '.data.policies | index("root")' >/dev/null; then
			bao_root token revoke -accessor "$accessor" >/dev/null
			changed "revoked a stray root token"
		fi
	done < <(bao_root list -format=json auth/token/accessors 2>/dev/null | jq -r '.[]')
}

configure_openbao() {
	local mount
	mount="$(env_require BAO_TRANSIT_MOUNT)"

	# The operator AppRole first: it is the way back in once the root token
	# of this session is revoked.
	if ! bao_root auth list -format=json | jq -e 'has("approle/")' >/dev/null; then
		bao_root auth enable approle >/dev/null
		changed "enabled the AppRole auth method"
	fi
	ensure_policy "$BAO_POLICY_BREAK_GLASS" "$(<"$INFRA_DIR/openbao/policies/break-glass.hcl")"
	ensure_operator_approle

	if ! bao_root secrets list -format=json | jq -e --arg m "$mount/" 'has($m)' >/dev/null; then
		bao_root secrets enable -path="$mount" transit >/dev/null
		changed "mounted the Transit engine at $mount/"
	fi
	# Encrypting with an unknown key name must fail, not create that key.
	bao_root write "$mount/config/keys" disable_upsert=true >/dev/null

	local utk_hours jwt_hours
	utk_hours=$(($(env_require KEY_ROTATION_UTK_DAYS) * 24))
	jwt_hours=$(($(env_require KEY_ROTATION_JWT_DAYS) * 24))
	ensure_transit_key "$(env_require BAO_KEY_UTK_ADMIN)" aes256-gcm96 true "$utk_hours"
	ensure_transit_key "$(env_require BAO_KEY_UTK_MEMBER)" aes256-gcm96 true "$utk_hours"
	ensure_transit_key "$(env_require BAO_KEY_SYSTEM)" aes256-gcm96 false 0
	ensure_transit_key "$(env_require BAO_KEY_JWT)" ed25519 false "$jwt_hours"

	ensure_policy "$BAO_POLICY_API" "$(render_policy "$INFRA_DIR/openbao/policies/api.hcl.tpl")"
	ensure_policy "$BAO_POLICY_WORKER" "$(render_policy "$INFRA_DIR/openbao/policies/worker.hcl.tpl")"

	ensure_approle "$BAO_ROLE_API" "$BAO_POLICY_API" 1h 24h
	ensure_approle "$BAO_ROLE_WORKER" "$BAO_POLICY_WORKER" 1h 24h
	ensure_approle_env "$BAO_ROLE_API" BAO_API_ROLE_ID BAO_API_SECRET_ID
	ensure_approle_env "$BAO_ROLE_WORKER" BAO_WORKER_ROLE_ID BAO_WORKER_SECRET_ID

	revoke_stray_root_tokens
}

# Without a root session: confirm that what the applications need is in place.
verify_openbao_unprivileged() {
	local mount role_var secret_var token name
	mount="$(env_require BAO_TRANSIT_MOUNT)"
	for role_var in BAO_API_ROLE_ID BAO_WORKER_ROLE_ID; do
		secret_var="${role_var/ROLE_ID/SECRET_ID}"
		token="$(bao_approle_login "$(env_get "$role_var")" "$(env_get "$secret_var")")" || return 1
		for name in BAO_KEY_UTK_ADMIN BAO_KEY_UTK_MEMBER BAO_KEY_SYSTEM BAO_KEY_JWT; do
			BAO_TOKEN="$token" bao_cli read -format=json "$mount/keys/$(env_require "$name")" >/dev/null 2>&1 || {
				bao_revoke_self "$token"
				return 1
			}
		done
		bao_revoke_self "$token"
	done
}

step_openbao() {
	step "4/6  OpenBao"
	bao_wait_api 120
	local status
	status="$(bao_status_json)"

	if [[ "$(jq -r .initialized <<<"$status")" == "false" ]]; then
		[[ "$RECOVERY_SHARES" =~ ^[1-9][0-9]*$ && "$RECOVERY_THRESHOLD" =~ ^[1-9][0-9]*$ ]] &&
			((RECOVERY_THRESHOLD <= RECOVERY_SHARES)) ||
			die "Invalid recovery key split: $RECOVERY_THRESHOLD of $RECOVERY_SHARES."
		local init index=0 share
		init="$(BAO_TOKEN="" bao_cli operator init -format=json \
			-recovery-shares="$RECOVERY_SHARES" -recovery-threshold="$RECOVERY_THRESHOLD")"
		BAO_ROOT_TOKEN="$(jq -r .root_token <<<"$init")"
		[[ -n "$BAO_ROOT_TOKEN" && "$BAO_ROOT_TOKEN" != "null" ]] || die "OpenBao init returned no root token."

		mkdir -p "$RECOVERY_DIR"
		chmod 700 "$RECOVERY_DIR"
		while IFS= read -r share; do
			index=$((index + 1))
			printf '%s\n' "$share" >"$RECOVERY_DIR/share-$index.key"
			chmod 600 "$RECOVERY_DIR/share-$index.key"
		done < <(jq -r '.recovery_keys_b64[]' <<<"$init")
		((index == RECOVERY_SHARES)) || die "OpenBao init returned $index recovery key shares, expected $RECOVERY_SHARES."
		init=""
		changed "initialised OpenBao ($RECOVERY_THRESHOLD of $RECOVERY_SHARES recovery key shares in $RECOVERY_DIR)"
	fi

	wait_healthy openbao 120

	if [[ -z "$BAO_ROOT_TOKEN" ]]; then
		if bao_root_available; then
			bao_root_open
		elif verify_openbao_unprivileged; then
			ok "OpenBao is initialised and both AppRoles work. Policies and key settings were not re-applied: no recovery key shares on this host (use --recovery-dir to apply changes)."
			return 0
		elif [[ ! -s "$OPERATOR_FILE" ]]; then
			die "OpenBao is initialised but $OPERATOR_FILE does not exist: a previous run was interrupted right after initialising it. It holds no keys yet. Remove it and re-run: infra/scripts/compose.sh rm -sf openbao && docker volume rm ${COMPOSE_PROJECT}_openbao_data && rm -rf $RECOVERY_DIR"
		else
			die "OpenBao needs (re)configuration but no recovery key shares are on this host. Re-run with --recovery-dir DIR pointing at the share-*.key files."
		fi
	fi

	configure_openbao
	bao_root_close
	ok "Transit keys, policies and AppRoles are in place; the root token is revoked"
}

# ── Step 5: Garage ────────────────────────────────────────────────────

step_garage() {
	if s3_external; then
		step "5/6  S3 (external service)"
		[[ -n "$(env_get S3_ACCESS_KEY_ID)" && -n "$(env_get S3_SECRET_ACCESS_KEY)" ]] ||
			die "SCANOVA_S3_MODE=external: set S3_ACCESS_KEY_ID and S3_SECRET_ACCESS_KEY (the bucket and key are made on the S3 service itself)."
		ok "using the external S3 service at $(env_require S3_ENDPOINT_INTERNAL), bucket $(env_require S3_BUCKET)"
		return 0
	fi
	step "5/6  Garage"
	wait_healthy garage 120
	local bucket bucket_info bucket_id key_id key_info
	bucket="$(env_require S3_BUCKET)"

	if ! bucket_info="$(garage_api GetBucketInfo "{\"globalAlias\":\"$bucket\"}" 2>/dev/null)" ||
		[[ -z "$(jq -r '.id // empty' <<<"$bucket_info" 2>/dev/null)" ]]; then
		bucket_info="$(garage_api CreateBucket "{\"globalAlias\":\"$bucket\"}")"
		changed "created bucket $bucket"
	fi
	bucket_id="$(jq -r .id <<<"$bucket_info")"

	key_id="$(env_get S3_ACCESS_KEY_ID)"
	if [[ -z "$key_id" ]] ||
		! key_info="$(garage_api GetKeyInfo "{\"id\":\"$key_id\",\"showSecretKey\":true}" 2>/dev/null)" ||
		[[ -z "$(jq -r '.accessKeyId // empty' <<<"$key_info" 2>/dev/null)" ]]; then
		key_info="$(garage_api CreateKey "{\"name\":\"$S3_KEY_NAME\"}")"
		key_id="$(jq -r .accessKeyId <<<"$key_info")"
		env_set S3_ACCESS_KEY_ID "$key_id"
		changed "created S3 access key $key_id"
	fi
	if [[ "$(env_get S3_SECRET_ACCESS_KEY)" != "$(jq -r .secretAccessKey <<<"$key_info")" ]]; then
		env_set S3_SECRET_ACCESS_KEY "$(jq -r .secretAccessKey <<<"$key_info")"
		changed "wrote S3_SECRET_ACCESS_KEY to .env"
	fi
	key_info=""

	# Read and write on this one bucket; not its owner, no bucket creation.
	if ! garage_api GetBucketInfo "{\"id\":\"$bucket_id\"}" | jq -e --arg k "$key_id" '
		.keys[] | select(.accessKeyId == $k) | .permissions
		| .read == true and .write == true and .owner == false' >/dev/null; then
		garage_api AllowBucketKey "{\"bucketId\":\"$bucket_id\",\"accessKeyId\":\"$key_id\",\"permissions\":{\"read\":true,\"write\":true}}" >/dev/null
		garage_api DenyBucketKey "{\"bucketId\":\"$bucket_id\",\"accessKeyId\":\"$key_id\",\"permissions\":{\"owner\":true}}" >/dev/null
		changed "granted the access key read/write on $bucket"
	fi
	ok "bucket '$bucket' and its access key are in place"
}

# ── Step 6: pairing ───────────────────────────────────────────────────

step_pairing() {
	step "6/6  Pairing"
	wait_healthy edge 120
	local url link fingerprint=""
	url="$(env_require SCANOVA_PUBLIC_URL)"
	link="scanova://pair?url=$(jq -rn --arg u "$url" '$u | @uri')"
	if [[ "$(env_get SCANOVA_TLS_MODE)" == "internal" ]]; then
		fingerprint="$(ca_fingerprint)"
		link="$link&ca=$fingerprint"
	fi

	printf '\n   %sServer URL%s       %s\n' "$C_BOLD" "$C_RESET" "$url"
	if [[ -n "$fingerprint" ]]; then
		printf '   %sCA fingerprint%s   %s\n' "$C_BOLD" "$C_RESET" "$fingerprint"
		printf '   %s(SHA-256 of the CA certificate; the app checks it against %s/.well-known/scanova/ca.crt)%s\n' "$C_DIM" "$url" "$C_RESET"
	else
		printf '   %sCertificate%s      public (ACME); the phone uses its system trust store\n' "$C_BOLD" "$C_RESET"
	fi
	printf '   %sPairing link%s     %s\n\n' "$C_BOLD" "$C_RESET" "$link"

	if ((SHOW_QR)); then
		if command -v qrencode >/dev/null 2>&1; then
			qrencode -t ANSIUTF8 -m 2 "$link"
		else
			info "Install 'qrencode' to print this link as a QR code."
		fi
	fi
}

summary() {
	step "Done"
	if ((${#CHANGES[@]} == 0)); then
		ok "Nothing to change: the stack was already fully configured."
	else
		info "Changes made in this run:"
		local line
		for line in "${CHANGES[@]}"; do
			info "  - $line"
		done
	fi
	info "Stack mode: $STACK_MODE. Check it with infra/scripts/smoke-test.sh"
	if [[ -n "$(recovery_shares)" && -z "${SCANOVA_RECOVERY_DIR:-}" ]]; then
		warn "OpenBao recovery key shares are stored on this host ($RECOVERY_DIR). Move them to separate offline places and delete them here (infra/README.md, \"After the first bootstrap\")."
	fi
}

main() {
	need openssl jq
	need_docker

	step_env
	mkdir -p "$STATE_DIR"
	chmod 700 "$STATE_DIR"
	# Published APKs, served read-only by the edge (/downloads/android/).
	install -d -m 755 "$STATE_DIR/downloads" "$STATE_DIR/downloads/android"
	resolve_mode "$MODE_FLAG"
	if [[ ! -f "$MODE_FILE" || "$(<"$MODE_FILE")" != "$STACK_MODE" ]]; then
		printf '%s\n' "$STACK_MODE" >"$MODE_FILE"
		changed "stack mode set to $STACK_MODE"
	fi

	step_pki
	step_seal_key
	step_services
	step_openbao
	step_garage
	step_pairing
	summary
}

main
