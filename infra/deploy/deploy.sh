#!/usr/bin/env bash
# Scanova — activates a release on the server. Runs as the `scanova` user,
# started by /opt/scanova/bin/deploy-gate (see infra/deploy/README.md).
#
#   deploy.sh activate <release-id>   configure, back up, build, start, check;
#                                     on failure, switch back to the previous release
#   deploy.sh rollback [<release-id>] re-activate an earlier release (default:
#                                     the one before the current release)
#
# Layout on the server:
#   /opt/scanova/releases/<id>/   code of each release (infra/.state and .env
#                                 are links into shared/)
#   /opt/scanova/current          link to the release that is live
#   /opt/scanova/shared/.env      the one environment file (§4)
#   /opt/scanova/shared/state/    PKI, vault seal/recovery, downloads
#   /opt/scanova/shared/backups/  database dumps taken before each deploy
#   /opt/scanova/shared/deploy.conf, s3.env   written by server-setup.sh (root)
set -euo pipefail

BASE=/opt/scanova
SHARED="$BASE/shared"
RELEASES="$BASE/releases"
KEEP_RELEASES=5
KEEP_BACKUPS=7
HEALTH_TIMEOUT=300

log() { printf '[deploy] %s\n' "$*"; }
fail() {
	printf '[deploy] error: %s\n' "$*" >&2
	exit 1
}

action="${1:-}"
target="${2:-}"

[[ -f "$SHARED/deploy.conf" ]] || fail "$SHARED/deploy.conf is missing: run server-setup.sh as root first."
[[ -f "$SHARED/s3.env" ]] || fail "$SHARED/s3.env is missing: run server-setup.sh as root first."
# shellcheck source=/dev/null
. "$SHARED/deploy.conf"
# shellcheck source=/dev/null
. "$SHARED/s3.env"

# Point a release at the shared environment file and state.
link_shared() {
	local rel="$1"
	ln -sfn "$SHARED/.env" "$rel/.env"
	rm -rf "$rel/infra/.state"
	ln -sfn "$SHARED/state" "$rel/infra/.state"
}

# The production values this server always runs with. Everything else in .env
# (generated secrets, AppRole ids) is kept as bootstrap.sh left it.
configure_env() {
	local rel="$1" tag="$2"
	if [[ ! -s "$SHARED/.env" ]]; then
		install -m 600 "$rel/.env.example" "$SHARED/.env"
		log "created $SHARED/.env from the template"
	fi
	(
		# shellcheck source=infra/scripts/lib.sh
		. "$rel/infra/scripts/lib.sh"
		env_set NODE_ENV production
		env_set SCANOVA_TLS_MODE public
		env_set SCANOVA_PUBLIC_URL "https://$API_HOST"
		env_set SCANOVA_PUBLIC_HOSTS "$API_HOST"
		env_set SCANOVA_SITE_HOST "$SITE_HOST"
		env_set SCANOVA_ADMIN_HOST "$ADMIN_HOST"
		env_set SCANOVA_HTTPS_PORT "$EDGE_PORT"
		env_set SCANOVA_BIND_ADDRESS 127.0.0.1
		env_set SCANOVA_S3_MODE external
		env_set S3_ENDPOINT_INTERNAL "$S3_ENDPOINT_INTERNAL"
		env_set S3_ENDPOINT "http://127.0.0.1:3900"
		env_set S3_REGION "$S3_REGION"
		env_set S3_BUCKET "$S3_BUCKET"
		env_set S3_ACCESS_KEY_ID "$S3_ACCESS_KEY_ID"
		env_set S3_SECRET_ACCESS_KEY "$S3_SECRET_ACCESS_KEY"
		env_set SCANOVA_IMAGE_TAG "$tag"
		# Open sign-up from the app, 2 GB per self-registered account (owner's decision).
		env_set SIGNUP_ENABLED true
	)
}

# compose.sh of a given release (its compose files, the shared .env).
stack() {
	local rel="$1"
	shift
	(cd "$rel" && infra/scripts/compose.sh "$@")
}

# The three app images, tagged with the release id. Built with `docker build`
# rather than `compose build`: Compose wants buildx 0.17+, Debian ships older.
# Same Dockerfiles and targets as the build sections in infra/compose.yaml.
build_images() {
	local rel="$1" id="$2"
	(
		cd "$rel"
		docker build --quiet -f apps/api/Dockerfile --target runtime -t "scanova/api:$id" . >/dev/null
		docker build --quiet -f apps/api/Dockerfile --target migrate -t "scanova/migrate:$id" . >/dev/null
		docker build --quiet -f apps/worker/Dockerfile --target runtime -t "scanova/worker:$id" . >/dev/null
	)
	log "images built: scanova/{api,migrate,worker}:$id"
}

# pg_dump of both databases, kept apart, before migrations run.
backup_databases() {
	local rel="$1" stamp id
	id="$(stack "$rel" ps -q postgres 2>/dev/null || true)"
	if [[ -z "$id" ]]; then
		log "no database running yet: nothing to back up"
		return 0
	fi
	stamp="$(date -u +%Y%m%dT%H%M%SZ)"
	install -d -m 700 "$SHARED/backups/main" "$SHARED/backups/keys"
	(
		. "$rel/infra/scripts/lib.sh"
		umask 077
		docker exec "$id" pg_dump -U postgres -Fc "$(env_get POSTGRES_DB)" >"$SHARED/backups/main/$stamp.dump"
		docker exec "$id" pg_dump -U postgres -Fc "$(env_get KEYS_POSTGRES_DB)" >"$SHARED/backups/keys/$stamp.dump"
	)
	for kind in main keys; do
		ls -1t "$SHARED/backups/$kind"/*.dump 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f || true
	done
	log "databases backed up ($stamp)"
}

# True when every service of the app profile came up as it should.
app_healthy() {
	local rel="$1"
	(
		cd "$rel"
		. infra/scripts/lib.sh
		local migrate
		migrate="$(service_container migrate)"
		[[ -n "$migrate" && "$(docker inspect -f '{{.State.ExitCode}}' "$migrate")" == "0" ]] || {
			compose logs --tail 40 migrate >&2 || true
			die "migrations failed"
		}
		wait_healthy api "$HEALTH_TIMEOUT"
		# The container healthcheck only proves the process is up. Readiness also
		# reaches the databases, object storage and the vault: without it a host
		# firewall that cut the API off from storage went unnoticed.
		local waited=0 readiness
		until readiness="$(compose exec -T api node -e "fetch('http://127.0.0.1:3000/api/v1/health/ready').then(async (r) => { process.stdout.write(await r.text()); process.exit(r.ok ? 0 : 1); }, () => process.exit(1))" 2>/dev/null)"; do
			((waited >= 60)) && die "the API is up but cannot reach its data services: ${readiness:-no answer}"
			sleep 5
			waited=$((waited + 5))
		done
		wait_healthy edge 120
		[[ "$(service_health worker)" == "running" || "$(service_health worker)" == "healthy" ]] || {
			compose logs --tail 40 worker >&2 || true
			die "the worker is not running"
		}
	)
}

switch_current() {
	ln -sfn "$1" "$BASE/current.new"
	mv -T "$BASE/current.new" "$BASE/current"
}

# Start a release whose images may already exist (rollback): build only what is missing.
start_release() {
	local rel="$1" id="$2"
	link_shared "$rel"
	configure_env "$rel" "$id"
	if ! docker image inspect "scanova/api:$id" "scanova/worker:$id" "scanova/migrate:$id" >/dev/null 2>&1; then
		build_images "$rel" "$id"
	fi
	stack "$rel" --profile app up -d --remove-orphans
}

prune() {
	local current_id old
	current_id="$(basename "$(readlink -f "$BASE/current")")"
	# grep exits 1 when only the current release exists; that is not an error.
	{
		ls -1 "$RELEASES" | grep -vE '\.partial$' | sort | grep -vx "$current_id" | head -n -$((KEEP_RELEASES - 1)) |
		while read -r old; do
			rm -rf "${RELEASES:?}/$old"
			docker image rm "scanova/api:$old" "scanova/worker:$old" "scanova/migrate:$old" >/dev/null 2>&1 || true
		done
	} || true
	docker image prune -f >/dev/null 2>&1 || true
}

activate() {
	local id="$1" rel="$RELEASES/$1" previous=""
	[[ -d "$rel" ]] || fail "no release $id"
	[[ -e "$BASE/current" ]] && previous="$(readlink -f "$BASE/current")"
	log "activating $id (previous: ${previous:+$(basename "$previous")}${previous:-none})"

	link_shared "$rel"
	configure_env "$rel" "$id"
	install -d -m 755 "$SHARED/state/downloads" "$SHARED/state/downloads/android"

	# Certificates, data services, vault, credentials. Idempotent.
	(cd "$rel" && infra/scripts/bootstrap.sh --prod --no-qr)
	backup_databases "$rel"

	log "building and starting the app (images tagged $id)"
	if build_images "$rel" "$id" && stack "$rel" --profile app up -d --remove-orphans && app_healthy "$rel"; then
		switch_current "$rel"
		prune
		log "live: $id"
		return 0
	fi

	if [[ -n "$previous" && "$previous" != "$rel" ]]; then
		log "the new release is unhealthy: switching back to $(basename "$previous")"
		start_release "$previous" "$(basename "$previous")"
		switch_current "$previous"
		app_healthy "$previous" || log "WARNING: the previous release is not healthy either"
	fi
	fail "release $id was not activated"
}

rollback() {
	local id="$1" rel
	if [[ -z "$id" ]]; then
		id="$(ls -1 "$RELEASES" | grep -vE '\.partial$' | sort | grep -B1 -x "$(basename "$(readlink -f "$BASE/current")")" | head -n 1)"
		[[ -n "$id" && "$id" != "$(basename "$(readlink -f "$BASE/current")")" ]] || fail "no earlier release to roll back to"
	fi
	rel="$RELEASES/$id"
	[[ -d "$rel" ]] || fail "no release $id"
	log "rolling back to $id"
	start_release "$rel" "$id"
	app_healthy "$rel" || fail "release $id is not healthy"
	switch_current "$rel"
	log "live: $id"
}

# One deploy at a time: a push can start CI while someone deploys by hand, and
# two first-time bootstraps on fresh state must never interleave.
exec 9>"$SHARED/.deploy.lock"
if ! flock -w 1800 9; then
	fail "another deploy has been running for 30 minutes; not starting a second one"
fi

case "$action" in
activate)
	[[ -n "$target" ]] || fail "usage: deploy.sh activate <release-id>"
	activate "$target"
	;;
rollback) rollback "$target" ;;
*) fail "usage: deploy.sh activate <release-id> | rollback [<release-id>]" ;;
esac
