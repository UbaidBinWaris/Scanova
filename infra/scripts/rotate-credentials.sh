#!/usr/bin/env bash
# Scanova credential rotation (docs/ARCHITECTURE.md §5.4, "Service
# credentials"). Replaces the credentials the api and worker use to reach the
# data services, writes the new values to the root .env and recreates the api
# and worker containers (if they are deployed) so they pick them up.
#
#   --db        passwords of the four PostgreSQL roles (main and key database)
#   --s3        the Garage access key (new key, old one deleted)
#   --approle   the OpenBao AppRole secret-ids (api, worker, operator); every
#               token issued through an old secret-id is revoked
#   --all       all of the above
#
# --approle needs a root token for a moment, so the OpenBao recovery key shares
# must be available (infra/.state/openbao/recovery or --recovery-dir).
# This script does not rotate encryption keys; that is the worker's job.

set -Eeuo pipefail
umask 077

# shellcheck source=infra/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DO_DB=0
DO_S3=0
DO_APPROLE=0
ASSUME_YES=0
MODE_FLAG=""

usage() {
	cat <<'EOF'
Usage: infra/scripts/rotate-credentials.sh (--db | --s3 | --approle | --all)… [options]

  --db                PostgreSQL role passwords (both databases).
  --s3                Garage access key.
  --approle           OpenBao AppRole secret-ids.
  --all               Everything.
  --recovery-dir DIR  Where the OpenBao recovery key shares are (for --approle).
  --yes               Do not ask for confirmation.
  --dev|--prod        Override the stack mode recorded by bootstrap.sh.
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
	--db) DO_DB=1 ;;
	--s3) DO_S3=1 ;;
	--approle) DO_APPROLE=1 ;;
	--all) DO_DB=1 DO_S3=1 DO_APPROLE=1 ;;
	--recovery-dir)
		export SCANOVA_RECOVERY_DIR="${2:?--recovery-dir needs a value}"
		shift
		;;
	--yes) ASSUME_YES=1 ;;
	--dev) MODE_FLAG="dev" ;;
	--prod) MODE_FLAG="prod" ;;
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

if ((!DO_DB && !DO_S3 && !DO_APPROLE)); then
	usage >&2
	exit 2
fi

trap 'bao_root_close' EXIT

# Recreates the api and worker containers that exist, so they start with the
# values now in .env. Nothing happens when the app profile is not deployed.
recreate_apps() {
	local service deployed=()
	for service in api worker; do
		if [[ -n "$(service_container "$service")" ]]; then
			deployed+=("$service")
		fi
	done
	if ((${#deployed[@]})); then
		compose --profile app up -d --no-deps "${deployed[@]}"
		ok "recreated: ${deployed[*]}"
	else
		info "api and worker are not deployed; they will read the new values when started."
	fi
}

rotate_db() {
	step "PostgreSQL role passwords"
	wait_healthy postgres 60
	local key
	for key in POSTGRES_APP_PASSWORD POSTGRES_MIGRATOR_PASSWORD; do
		env_set "$key" "$(rand_hex 24)"
	done
	provision_database "$(env_require POSTGRES_DB)" \
		"$(env_require POSTGRES_MIGRATOR_USER)" "$(env_require POSTGRES_MIGRATOR_PASSWORD)" \
		"$(env_require POSTGRES_APP_USER)" "$(env_require POSTGRES_APP_PASSWORD)"
	ok "main database: new passwords for $(env_get POSTGRES_MIGRATOR_USER) and $(env_get POSTGRES_APP_USER)"

	if [[ "$(keys_db_location)" == "bundled" ]]; then
		for key in KEYS_POSTGRES_APP_PASSWORD KEYS_POSTGRES_MIGRATOR_PASSWORD; do
			env_set "$key" "$(rand_hex 24)"
		done
		provision_database "$(env_require KEYS_POSTGRES_DB)" \
			"$(env_require KEYS_POSTGRES_MIGRATOR_USER)" "$(env_require KEYS_POSTGRES_MIGRATOR_PASSWORD)" \
			"$(env_require KEYS_POSTGRES_APP_USER)" "$(env_require KEYS_POSTGRES_APP_PASSWORD)"
		ok "key database: new passwords for $(env_get KEYS_POSTGRES_MIGRATOR_USER) and $(env_get KEYS_POSTGRES_APP_USER)"
	else
		warn "The key database is on another instance ($(env_get KEYS_POSTGRES_HOST):$(env_get KEYS_POSTGRES_PORT)); its two passwords were NOT changed. Set new values for KEYS_POSTGRES_APP_PASSWORD and KEYS_POSTGRES_MIGRATOR_PASSWORD in .env and run infra/postgres/provision-database.sql on that instance."
	fi
}

rotate_s3() {
	step "Garage access key"
	if s3_external; then
		warn "SCANOVA_S3_MODE=external: the access key belongs to the external S3 service. Rotate it there (on the Scanova server: infra/deploy/server-setup.sh --rotate-s3-key, as root), then deploy."
		return 0
	fi
	wait_healthy garage 60
	local bucket bucket_id old_id new_key new_id
	bucket="$(env_require S3_BUCKET)"
	bucket_id="$(garage_api GetBucketInfo "{\"globalAlias\":\"$bucket\"}" | jq -r .id)"
	[[ -n "$bucket_id" && "$bucket_id" != "null" ]] || die "Bucket '$bucket' does not exist. Run infra/scripts/bootstrap.sh."
	old_id="$(env_get S3_ACCESS_KEY_ID)"

	new_key="$(garage_api CreateKey "{\"name\":\"$S3_KEY_NAME\"}")"
	new_id="$(jq -r .accessKeyId <<<"$new_key")"
	garage_api AllowBucketKey "{\"bucketId\":\"$bucket_id\",\"accessKeyId\":\"$new_id\",\"permissions\":{\"read\":true,\"write\":true}}" >/dev/null
	env_set S3_ACCESS_KEY_ID "$new_id"
	env_set S3_SECRET_ACCESS_KEY "$(jq -r .secretAccessKey <<<"$new_key")"
	new_key=""
	ok "new access key $new_id has read/write on '$bucket'"
	S3_OLD_KEY_ID="$old_id"
}

# After the applications run on the new key.
retire_old_s3_key() {
	if [[ -n "${S3_OLD_KEY_ID:-}" && "$S3_OLD_KEY_ID" != "$(env_get S3_ACCESS_KEY_ID)" ]]; then
		if garage_api DeleteKey "{\"id\":\"$S3_OLD_KEY_ID\"}" >/dev/null 2>&1; then
			ok "deleted the old access key $S3_OLD_KEY_ID"
		else
			warn "Could not delete the old access key $S3_OLD_KEY_ID; remove it with: garage key delete --yes $S3_OLD_KEY_ID"
		fi
	fi
}

# new_secret_id ROLE — issues a secret-id and destroys every other one of
# that role. Prints the new secret-id.
new_secret_id() {
	local role="$1" issued accessor other
	issued="$(bao_root write -f -format=json "auth/approle/role/$role/secret-id")"
	accessor="$(jq -r .data.secret_id_accessor <<<"$issued")"
	while IFS= read -r other; do
		[[ -n "$other" && "$other" != "$accessor" ]] || continue
		bao_root write "auth/approle/role/$role/secret-id-accessor/destroy" "secret_id_accessor=$other" >/dev/null
	done < <(bao_root list -format=json "auth/approle/role/$role/secret-id" 2>/dev/null | jq -r '.[]')
	jq -r .data.secret_id <<<"$issued"
}

rotate_approle() {
	step "OpenBao AppRole secret-ids"
	wait_healthy openbao 60
	bao_root_available ||
		die "A root token is needed, but the recovery key shares are not on this host. Put at least the threshold number of share-*.key files in a directory and pass --recovery-dir DIR."
	bao_root_open

	env_set BAO_API_SECRET_ID "$(new_secret_id "$BAO_ROLE_API")"
	ok "new secret-id for $BAO_ROLE_API (old ones destroyed)"
	env_set BAO_WORKER_SECRET_ID "$(new_secret_id "$BAO_ROLE_WORKER")"
	ok "new secret-id for $BAO_ROLE_WORKER (old ones destroyed)"

	ROLE_ID="$(jq -r .role_id "$OPERATOR_FILE")" SECRET_ID="$(new_secret_id "$BAO_ROLE_OPERATOR")" \
		jq -n '{role_id: env.ROLE_ID, secret_id: env.SECRET_ID}' >"$OPERATOR_FILE.new"
	chmod 600 "$OPERATOR_FILE.new"
	mv -f "$OPERATOR_FILE.new" "$OPERATOR_FILE"
	ok "new secret-id for $BAO_ROLE_OPERATOR (old ones destroyed)"

	# Tokens obtained with the old secret-ids would otherwise live until they
	# expire. This ends every AppRole session; the root token is not one.
	bao_root lease revoke -prefix -sync auth/approle/login >/dev/null
	ok "revoked every token issued through AppRole logins"

	bao_root_close
	ok "the root token is revoked"
}

main() {
	need openssl jq
	need_docker
	[[ -f "$ENV_FILE" ]] || die "$ENV_FILE does not exist. Run infra/scripts/bootstrap.sh first."
	resolve_mode "$MODE_FLAG"

	local what=()
	if ((DO_DB)); then what+=("PostgreSQL role passwords"); fi
	if ((DO_S3)); then what+=("Garage access key"); fi
	if ((DO_APPROLE)); then what+=("OpenBao AppRole secret-ids"); fi
	step "Rotate service credentials"
	local item
	for item in "${what[@]}"; do info "- $item"; done
	info "The api and worker containers are recreated if they are deployed (a short interruption)."
	if ((!ASSUME_YES)); then
		[[ -t 0 ]] || die "Refusing to rotate without confirmation: no terminal. Use --yes to skip the prompt."
		read -r -p "   Continue? [y/N] " answer
		[[ "$answer" == "y" || "$answer" == "Y" ]] || die "Not confirmed. Nothing was changed."
	fi

	if ((DO_DB)); then rotate_db; fi
	if ((DO_S3)); then rotate_s3; fi
	if ((DO_APPROLE)); then rotate_approle; fi

	step "Applications"
	recreate_apps
	if ((DO_S3)); then retire_old_s3_key; fi

	step "Done"
	info "New values are in $ENV_FILE. Verify with infra/scripts/smoke-test.sh"
}

main
