#!/bin/sh
# One-shot `ai-init` service (infra/compose.yaml, profiles ai and ai-tools).
# Runs before the llama.cpp services every time they are brought up.
#
# Like infra/init/prepare-volumes.sh for the core stack, it is root for a
# moment, with no network, a read-only root filesystem and only CHOWN,
# DAC_OVERRIDE and FOWNER, and it does two things:
#   1. gives the model volume to the AI user (the downloads in it are written by
#      infra/scripts/ai-models.sh as that user and only read by the runtimes);
#   2. writes the runtime API key from AI_API_KEY into a volume only the AI
#      services mount, readable by the AI user only (mode 0400). llama.cpp reads
#      it with --api-key-file. Compose cannot hand an environment-sourced secret
#      to a read-only container, so this is where the file comes from.

set -eu

fail() {
	echo "ai-init: $*" >&2
	exit 1
}

: "${AI_ID:?}"
key="${AI_API_KEY:-}"
[ -n "$key" ] || fail "AI_API_KEY is empty in the root .env. Run infra/scripts/bootstrap.sh, which generates it."
[ "${#key}" -ge 32 ] || fail "AI_API_KEY must be at least 32 characters."
case "$key" in
*[!A-Za-z0-9._~+/=-]*) fail "AI_API_KEY may only contain letters, digits and ._~+/=-" ;;
esac

chown "$AI_ID" /out/models
chmod 0750 /out/models

chown "$AI_ID" /out/secrets
chmod 0700 /out/secrets
target=/out/secrets/api-key
# Rewritten only when the key changed, atomically; one key per line, no other content.
if [ ! -f "$target" ] || [ "$(cat "$target")" != "$key" ]; then
	printf '%s\n' "$key" >"$target.tmp"
	chown "$AI_ID" "$target.tmp"
	chmod 0400 "$target.tmp"
	mv -f "$target.tmp" "$target"
fi
chown "$AI_ID" "$target"
chmod 0400 "$target"

echo "ai-init: model volume and API key are in place"
