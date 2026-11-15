#!/usr/bin/env bash
# Scanova reset — DEVELOPMENT teardown. Destroys everything bootstrap.sh
# created so the next bootstrap starts from nothing:
#
#   - the stack's containers and networks,
#   - its volumes: both databases, every stored object, the vault and its
#     master keys,
#   - infra/.state: internal CA, certificates, unseal key, recovery key shares.
#
# After this, every document ever stored is unrecoverable and every phone must
# be paired again. .env is kept unless --purge-env is given.

set -Eeuo pipefail

# shellcheck source=infra/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ASSUME_YES=0
ALLOW_PROD=0
PURGE_ENV=0

usage() {
	cat <<'EOF'
Usage: infra/scripts/reset.sh [--yes] [--purge-env] [--allow-prod]

  --yes         Do not ask for confirmation (development mode only).
  --purge-env   Also delete the root .env.
  --allow-prod  Let --yes work on a stack that bootstrap.sh set up without
                --dev. Without it such a stack is only reset interactively.
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
	--yes) ASSUME_YES=1 ;;
	--purge-env) PURGE_ENV=1 ;;
	--allow-prod) ALLOW_PROD=1 ;;
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

need_docker
resolve_mode ""

# Selected by the Compose project label, so this works without a valid .env
# and never touches another project (for example the `scanova-test` stack).
label="label=com.docker.compose.project=$COMPOSE_PROJECT"
mapfile -t containers < <(docker ps -aq --filter "$label")
mapfile -t volumes < <(docker volume ls -q --filter "$label")
mapfile -t networks < <(docker network ls -q --filter "$label")

step "Reset the '$COMPOSE_PROJECT' stack (mode: $STACK_MODE)"
info "containers: ${#containers[@]}   volumes: ${#volumes[@]}   networks: ${#networks[@]}"
if ((${#volumes[@]})); then
	docker volume ls --filter "$label" --format '     {{.Name}}'
fi
if [[ -d "$STATE_DIR" ]]; then info "state:      $STATE_DIR"; else info "state:      (none)"; fi
if ((PURGE_ENV)); then info "env file:   $ENV_FILE (will be deleted)"; else info "env file:   kept"; fi
warn "This permanently destroys all documents, both databases, the vault's master keys and the internal CA."

if ((ASSUME_YES)) && [[ -f "$MODE_FILE" && "$STACK_MODE" == "prod" ]] && ((!ALLOW_PROD)); then
	warn "This stack was not set up with --dev, so --yes is ignored (add --allow-prod to override)."
	ASSUME_YES=0
fi
if ((!ASSUME_YES)); then
	[[ -t 0 ]] || die "Refusing to reset without confirmation: no terminal. Use --yes to skip the prompt."
	read -r -p "   Type '$COMPOSE_PROJECT' to destroy it: " answer
	[[ "$answer" == "$COMPOSE_PROJECT" ]] || die "Not confirmed. Nothing was changed."
fi

if ((${#containers[@]})); then
	docker rm -f -v "${containers[@]}" >/dev/null
	ok "removed ${#containers[@]} container(s)"
fi
if ((${#volumes[@]})); then
	docker volume rm "${volumes[@]}" >/dev/null
	ok "removed ${#volumes[@]} volume(s)"
fi
if ((${#networks[@]})); then
	docker network rm "${networks[@]}" >/dev/null
	ok "removed ${#networks[@]} network(s)"
fi

if [[ -d "$STATE_DIR" ]]; then
	[[ "$STATE_DIR" == */infra/.state ]] || die "Unexpected state directory '$STATE_DIR'; not deleting it."
	rm -rf -- "$STATE_DIR"
	ok "deleted $STATE_DIR"
fi

if ((PURGE_ENV)) && [[ -f "$ENV_FILE" ]]; then
	rm -f -- "$ENV_FILE"
	ok "deleted $ENV_FILE"
elif [[ -f "$ENV_FILE" ]]; then
	info "$ENV_FILE is kept. Its vault and storage credentials now point at nothing; the next bootstrap replaces them."
fi

step "Done"
info "Start again with infra/scripts/bootstrap.sh"
