#!/usr/bin/env bash
# `docker compose` for the Scanova stack, with the root .env and the right
# compose files already selected. Everything after the optional mode flag is
# passed through unchanged:
#
#   infra/scripts/compose.sh ps
#   infra/scripts/compose.sh --profile app up -d --build
#   infra/scripts/compose.sh logs -f openbao
#   infra/scripts/compose.sh --profile app run --rm api node dist/cli.js <command>
#
# The mode (dev = also infra/compose.dev.yaml) is the one bootstrap.sh
# recorded; --dev or --prod as the FIRST argument overrides it for one call.

set -Eeuo pipefail

# shellcheck source=infra/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

mode=""
case "${1:-}" in
--dev)
	mode="dev"
	shift
	;;
--prod)
	mode="prod"
	shift
	;;
esac

need_docker
[[ -f "$ENV_FILE" ]] || die "$ENV_FILE does not exist. Run infra/scripts/bootstrap.sh first."
resolve_mode "$mode"
compose "$@"
