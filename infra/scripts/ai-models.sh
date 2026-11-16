#!/usr/bin/env bash
# AI models for the llama.cpp services (Compose profile `ai`, docs/ARCHITECTURE.md §11).
#
#   infra/scripts/ai-models.sh list                       the catalogue: candidates, sizes, licences
#   infra/scripts/ai-models.sh fetch NAME... [--yes]      download into the ai_models volume, SHA-256-checked
#   infra/scripts/ai-models.sh use CHAT EMBED [--no-env]  make a pair active (links + AI_*_MODEL in .env)
#   infra/scripts/ai-models.sh status | verify            what is in the volume; re-check every hash
#   infra/scripts/ai-models.sh remove NAME...             delete a candidate's files
#   infra/scripts/ai-models.sh up | down                  start / stop ai-chat and ai-embed
#   infra/scripts/ai-models.sh dataset [--out DIR]        generate the evaluation dataset (needs internet)
#   infra/scripts/ai-models.sh eval CHAT EMBED [--dataset DIR] [--out DIR] [-- RUNNER ARGS]
#                                                         run tools/ai-eval against that pair
#
# Every file is pinned three ways: the Hugging Face repository commit in the URL,
# the byte size and the SHA-256. A download that differs in any of them is
# deleted. Downloads run in the one-shot `ai-fetch` container (the only AI
# container with a route out); the runtimes themselves have none and start with
# --offline. Nothing here needs root. See tools/ai-eval/README.md.

set -Eeuo pipefail
umask 077

# shellcheck source=infra/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

EVAL_STATE="$STATE_DIR/ai-eval"
# python:3.13.18-slim-trixie (2026-11-08), only for `dataset`.
PYTHON_IMAGE="python:3.13.18-slim-trixie@sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90"

# name|role|repository|revision|file|bytes|sha256|licence
# role: chat (the model), mmproj (its vision projector), embed. Sources and
# reasons: docs/ai-research.md. The smoke-* pair is tiny and only proves the
# plumbing (quality is meaningless).
CATALOGUE='
gemma-4-e4b|chat|google/gemma-4-E4B-it-qat-q4_0-gguf|4b4a2c1d584be7264f87aac328a1bc739ce81b6c|gemma-4-E4B_q4_0-it.gguf|5154941280|676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee|Apache-2.0
gemma-4-e4b|mmproj|google/gemma-4-E4B-it-qat-q4_0-gguf|4b4a2c1d584be7264f87aac328a1bc739ce81b6c|gemma-4-E4B-it-mmproj.gguf|991552256|7498a37cb619e55f2fcf87eb931f56e99389ed6d432e4c5c66110694c0d65578|Apache-2.0
gemma-4-e2b|chat|google/gemma-4-E2B-it-qat-q4_0-gguf|675cff42a74c774d6cb76f76d8eacb49b48c9b93|gemma-4-E2B_q4_0-it.gguf|3349516256|fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634|Apache-2.0
gemma-4-e2b|mmproj|google/gemma-4-E2B-it-qat-q4_0-gguf|675cff42a74c774d6cb76f76d8eacb49b48c9b93|gemma-4-E2B-it-mmproj.gguf|986833664|021059cce659fe7f9170d5599761d7bbaf644b798dab9503aca30dc43e6beb14|Apache-2.0
qwen3.5-4b|chat|ggml-org/Qwen3.5-4B-GGUF|8d43d974efc48399cb58957ad49c8b26bf5a109c|Qwen3.5-4B-Q4_K_M.gguf|3032091584|217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14|Apache-2.0
qwen3.5-4b|mmproj|ggml-org/Qwen3.5-4B-GGUF|8d43d974efc48399cb58957ad49c8b26bf5a109c|mmproj-Qwen3.5-4B-Q8_0.gguf|366894560|5a5ba634d46f9888599963bfc52194eadf099e1efdbee46d71957d911cf62654|Apache-2.0
qwen3.5-2b|chat|unsloth/Qwen3.5-2B-GGUF|f6d5376be1edb4d416d56da11e5397a961aca8ae|Qwen3.5-2B-Q4_K_M.gguf|1280835840|aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223|Apache-2.0
qwen3.5-2b|mmproj|unsloth/Qwen3.5-2B-GGUF|f6d5376be1edb4d416d56da11e5397a961aca8ae|mmproj-F16.gguf|668227264|7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7|Apache-2.0
ministral-3-3b|chat|mistralai/Ministral-3-3B-Instruct-2512-GGUF|eb599d408350ea2bb60452cb86be7c7b2fc28227|Ministral-3-3B-Instruct-2512-Q4_K_M.gguf|2147023008|9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8|Apache-2.0
ministral-3-3b|mmproj|mistralai/Ministral-3-3B-Instruct-2512-GGUF|eb599d408350ea2bb60452cb86be7c7b2fc28227|Ministral-3-3B-Instruct-2512-BF16-mmproj.gguf|841501856|70ca26e41ecea9da997e2887462ad68b848fbf64da011ff4107bbe7353519ee0|Apache-2.0
phi-4-mini|chat|unsloth/Phi-4-mini-instruct-GGUF|78eb92a46fc37e6b524df991ed9aca9bc6aa7b80|Phi-4-mini-instruct-Q4_K_M.gguf|2491874272|88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a|MIT
qwen3-embedding-0.6b|embed|Qwen/Qwen3-Embedding-0.6B-GGUF|370f27d7550e0def9b39c1f16d3fbaa13aa67728|Qwen3-Embedding-0.6B-Q8_0.gguf|639150592|06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439|Apache-2.0
embeddinggemma-2|embed|ggml-org/embeddinggemma-2-GGUF|6ea3e3a309d12a4779cfda98b8c19800d2d5da71|embeddinggemma-2-Q8_0.gguf|309855456|2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135|Apache-2.0
multilingual-e5-small|embed|cstr/multilingual-e5-small-GGUF|178420da727e544c2689e89b6648b205ad176eda|multilingual-e5-small-q8_0.gguf|131624960|0a34067a40f25d3149b36885faa62bee0e5284d0f9edc102acfc00e115d953e8|MIT
multilingual-e5-base|embed|cstr/multilingual-e5-base-GGUF|da4250447f282af221d862c22984cfdcab15c637|multilingual-e5-base-q8_0.gguf|301746048|30d86626b15209ccabd0ff286153b32cfa3e7509e787a8e7d85d031275722abf|MIT
bge-m3|embed|ggml-org/bge-m3-Q8_0-GGUF|9eba04c5d75ba5a1595e45de734d36bef4e5cb98|bge-m3-q8_0.gguf|634553760|aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173|MIT
smoke-smolvlm|chat|ggml-org/SmolVLM-256M-Instruct-GGUF|b9e4379657e1450d04d02eec8e345667265b0a00|SmolVLM-256M-Instruct-Q8_0.gguf|175054528|2a31195d3769c0b0fd0a4906201666108834848db768af11de1d2cef7cd35e65|Apache-2.0
smoke-smolvlm|mmproj|ggml-org/SmolVLM-256M-Instruct-GGUF|b9e4379657e1450d04d02eec8e345667265b0a00|mmproj-SmolVLM-256M-Instruct-Q8_0.gguf|103769856|7e943f7c53f0382a6fc41b6ee0c2def63ba4fded9ab8ed039cc9e2ab905e0edd|Apache-2.0
smoke-bge-small|embed|CompendiumLabs/bge-small-en-v1.5-gguf|d32f8c040ea3b516330eeb75b72bcc2d3a780ab7|bge-small-en-v1.5-q8_0.gguf|36806944|ec38e8da142596baa913124ae50550de284b6916bf59577ef2f0cb9660c2f514|MIT
'

# Runs inside ai-fetch (busybox sh + curl). Inputs come from the environment.
# shellcheck disable=SC2016 # expanded by the container's shell
FETCH_SCRIPT='
dir="/models/$F_NAME"
target="$dir/$F_FILE"
mkdir -p "$dir" /models/.partial
if [ -f "$target" ] && [ "$(stat -c %s "$target")" = "$F_SIZE" ] && echo "$F_SHA256  $target" | sha256sum -c -s; then
	echo "   present  $F_NAME/$F_FILE"
	exit 0
fi
part="/models/.partial/$F_NAME--$F_FILE"
echo "   fetching $F_NAME/$F_FILE"
curl --fail --location --proto "=https" --proto-redir "=https" --tlsv1.2 --retry 5 --retry-delay 10 \
	--retry-all-errors --continue-at - --progress-bar --output "$part" "$F_URL"
size="$(stat -c %s "$part")"
if [ "$size" != "$F_SIZE" ]; then
	echo "   size $size is not the pinned $F_SIZE: deleted" >&2
	rm -f "$part"
	exit 1
fi
if ! echo "$F_SHA256  $part" | sha256sum -c -s; then
	echo "   SHA-256 does not match the pinned value: deleted" >&2
	rm -f "$part"
	exit 1
fi
chmod 0440 "$part"
mv -f "$part" "$target"
echo "   verified $F_NAME/$F_FILE"
'

usage() { sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; }

catalogue() { grep -v -e '^$' <<<"$CATALOGUE"; }

# entries NAME — the catalogue lines of one candidate (fails for an unknown name).
entries() {
	local found
	found="$(catalogue | awk -F'|' -v n="$1" '$1 == n')"
	[[ -n "$found" ]] || die "Unknown model '$1'. See: infra/scripts/ai-models.sh list"
	printf '%s\n' "$found"
}

kind_of() { entries "$1" | awk -F'|' '$2 == "chat" || $2 == "embed" { print $2; exit }'; }

human() { awk -v b="$1" 'BEGIN { split("B KB MB GB TB", u); i = 1; while (b >= 1000 && i < 5) { b /= 1000; i++ } printf (i == 1 ? "%d %s" : "%.2f %s"), b, u[i] }'; }

tools() { compose --profile ai-tools "$@"; }

# in_volume SCRIPT [ENV=VALUE…] — runs a busybox script in ai-fetch with the model
# volume, as the AI user. ai-init runs first (it owns the volume to that user).
in_volume() {
	local script="$1" args=()
	shift
	local pair
	for pair in "$@"; do args+=(-e "$pair"); done
	# stdin from /dev/null: `compose run` would otherwise eat the caller's loop input.
	tools run --rm -T "${args[@]}" ai-fetch "$script" </dev/null
}

docker_free_bytes() {
	local root
	root="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)"
	df -B1 --output=avail "$root" 2>/dev/null | tail -n 1 | tr -d ' '
}

# ── commands ──────────────────────────────────────────────────────────

cmd_list() {
	printf '%-22s %-6s %-11s %10s  %s\n' NAME KIND LICENCE SIZE SOURCE
	local name
	for name in $(catalogue | cut -d'|' -f1 | awk '!seen[$0]++'); do
		local total licence repo
		total="$(entries "$name" | awk -F'|' '{ s += $6 } END { print s }')"
		licence="$(entries "$name" | head -n 1 | cut -d'|' -f8)"
		repo="$(entries "$name" | head -n 1 | cut -d'|' -f3)"
		printf '%-22s %-6s %-11s %10s  https://huggingface.co/%s\n' "$name" "$(kind_of "$name")" "$licence" "$(human "$total")" "$repo"
	done
}

cmd_fetch() {
	local yes=0 names=() name
	for name in "$@"; do
		case "$name" in
		--yes | -y) yes=1 ;;
		*) names+=("$name") ;;
		esac
	done
	((${#names[@]} > 0)) || die "Name at least one model. See: infra/scripts/ai-models.sh list"
	local total=0 line
	for name in "${names[@]}"; do
		while IFS= read -r line; do total=$((total + $(cut -d'|' -f6 <<<"$line"))); done < <(entries "$name")
	done
	local free
	free="$(docker_free_bytes)"
	info "Requested: ${names[*]} — up to $(human "$total") (files already present are skipped)."
	if [[ "$free" =~ ^[0-9]+$ ]]; then
		info "Free space where Docker keeps volumes: $(human "$free")."
		((free > total + 2000000000)) || die "Not enough free disk space (keeps 2 GB spare)."
	fi
	if ((!yes)); then
		[[ -t 0 ]] || die "Confirm the download with --yes."
		read -r -p "   Download now? [y/N] " answer
		[[ "$answer" == [yY]* ]] || die "Cancelled."
	fi
	step "Fetching into the ai_models volume"
	for name in "${names[@]}"; do
		while IFS='|' read -r n _ repo revision file bytes sha _; do
			[[ -n "$n" ]] || continue
			in_volume "$FETCH_SCRIPT" "F_NAME=$n" "F_FILE=$file" "F_SIZE=$bytes" "F_SHA256=$sha" \
				"F_URL=https://huggingface.co/$repo/resolve/$revision/$file"
		done < <(entries "$name")
	done
	ok "fetched: ${names[*]}"
}

# links CHAT EMBED — points chat.gguf, chat-mmproj.gguf and embed.gguf at a pair.
links() {
	local chat="$1" embed="$2" chat_file mmproj_file embed_file
	[[ "$(kind_of "$chat")" == "chat" ]] || die "'$chat' is not a chat model."
	[[ "$(kind_of "$embed")" == "embed" ]] || die "'$embed' is not an embedding model."
	chat_file="$chat/$(entries "$chat" | awk -F'|' '$2 == "chat" { print $5 }')"
	mmproj_file="$(entries "$chat" | awk -F'|' '$2 == "mmproj" { print $5 }')"
	[[ -z "$mmproj_file" ]] || mmproj_file="$chat/$mmproj_file"
	embed_file="$embed/$(entries "$embed" | awk -F'|' '$2 == "embed" { print $5 }')"
	# shellcheck disable=SC2016 # expanded by the container's shell
	in_volume '
cd /models
for f in "$L_CHAT" "$L_EMBED" ${L_MMPROJ:+"$L_MMPROJ"}; do
	[ -f "$f" ] || { echo "   missing $f: fetch it first" >&2; exit 1; }
done
ln -sfn "$L_CHAT" .chat.gguf && mv -f .chat.gguf chat.gguf
if [ -n "$L_MMPROJ" ]; then ln -sfn "$L_MMPROJ" .chat-mmproj.gguf && mv -f .chat-mmproj.gguf chat-mmproj.gguf; else rm -f chat-mmproj.gguf; fi
ln -sfn "$L_EMBED" .embed.gguf && mv -f .embed.gguf embed.gguf
echo "   chat.gguf -> $L_CHAT"
[ -z "$L_MMPROJ" ] || echo "   chat-mmproj.gguf -> $L_MMPROJ"
echo "   embed.gguf -> $L_EMBED"
' "L_CHAT=$chat_file" "L_MMPROJ=$mmproj_file" "L_EMBED=$embed_file"
}

cmd_use() {
	local chat="${1:-}" embed="${2:-}" no_env=0
	[[ -n "$chat" && -n "$embed" ]] || die "Usage: ai-models.sh use CHAT EMBED [--no-env]"
	[[ "${3:-}" == "--no-env" ]] && no_env=1
	step "Active pair: $chat + $embed"
	links "$chat" "$embed"
	if ((!no_env)); then
		env_set AI_CHAT_MODEL "$chat"
		env_set AI_EMBED_MODEL "$embed"
		ok "AI_CHAT_MODEL and AI_EMBED_MODEL set in .env"
	fi
	info "Restart the runtimes to load it: infra/scripts/ai-models.sh up"
}

cmd_status() {
	# shellcheck disable=SC2016 # expanded by the container's shell
	in_volume '
cd /models
for l in chat.gguf chat-mmproj.gguf embed.gguf; do
	if [ -L "$l" ]; then echo "   $l -> $(readlink "$l")"; else echo "   $l (not set)"; fi
done
find . -mindepth 2 -type f ! -path "./.partial/*" -exec ls -l {} \; | awk "{ printf \"   %12s  %s\n\", \$5, \$9 }"
[ -z "$(ls -A .partial 2>/dev/null)" ] || echo "   unfinished downloads in .partial (fetch again to resume)"
df -h /models | tail -n 1 | awk "{ print \"   volume free: \" \$4 }"
'
	local service
	for service in ai-chat ai-embed; do
		info "$service: $(service_health "$service")"
	done
}

cmd_verify() {
	local list
	list="$(catalogue | awk -F'|' '{ printf "%s  /models/%s/%s\n", $7, $1, $5 }')"
	# shellcheck disable=SC2016 # expanded by the container's shell
	in_volume '
printf "%s\n" "$V_LIST" | while read -r sha path; do [ -f "$path" ] && echo "$sha  $path"; done > /tmp/present
[ -s /tmp/present ] || { echo "   no model files"; exit 0; }
sha256sum -c /tmp/present
' "V_LIST=$list"
}

cmd_remove() {
	(($# > 0)) || die "Name at least one model."
	local name
	for name in "$@"; do
		entries "$name" >/dev/null
		# shellcheck disable=SC2016 # expanded by the container's shell
		in_volume 'rm -rf "/models/$R_NAME" "/models/.partial/$R_NAME--"*; echo "   removed $R_NAME"' "R_NAME=$name"
	done
	warn "Links that pointed at removed files are now broken; run 'use' before 'up'."
}

cmd_up() {
	step "Starting ai-chat and ai-embed"
	compose --profile ai up -d "$@" ai-chat ai-embed
	wait_healthy ai-embed 120
	wait_healthy ai-chat 120
	ok "AI runtimes are up, with no model in memory"
	info "An owner loads them from the app (Settings → Server AI); see owner:grant in infra/README.md"
}

cmd_down() {
	compose --profile ai stop ai-chat ai-embed
	compose --profile ai rm -f ai-chat ai-embed >/dev/null
	ok "AI runtimes stopped (the models stay in the volume)"
}

cmd_dataset() {
	local out="$EVAL_STATE/dataset"
	[[ "${1:-}" == "--out" ]] && out="${2:?--out needs a directory}"
	mkdir -p "$out"
	out="$(cd "$out" && pwd)"
	step "Generating the evaluation dataset in $out"
	info "Throwaway container with internet access: Debian's FriBiDi, Pillow and fontTools pinned by hash,"
	info "OFL fonts pinned by SHA-256 (tools/ai-eval/requirements-generate.txt, ai_eval/fonts.py)."
	# Root inside the container only to install FriBiDi; the output is handed to this user.
	# shellcheck disable=SC2016 # expanded by the container's shell
	docker run --rm --network bridge \
		-v "$ROOT_DIR/tools/ai-eval:/eval:ro" -v "$out:/out" \
		-e "HOST_ID=$(id -u):$(id -g)" -e HOME=/tmp -e PIP_DISABLE_PIP_VERSION_CHECK=1 \
		"$PYTHON_IMAGE" sh -euc '
apt-get update -qq
apt-get install -y -qq --no-install-recommends libfribidi0 >/dev/null
pip install --quiet --no-cache-dir --root-user-action=ignore --require-hashes -r /eval/requirements-generate.txt
python -I /eval/aieval.py generate --out /out --font-cache /tmp/fonts
chown -R "$HOST_ID" /out'
	ok "dataset ready: $out"
}

# cgroup_dir SERVICE — the cgroup v2 directory of a running service's container, if readable.
cgroup_dir() {
	local id dir
	id="$(service_container "$1")"
	[[ -n "$id" ]] || return 0
	id="$(docker inspect -f '{{.Id}}' "$id")"
	for dir in "/sys/fs/cgroup/system.slice/docker-$id.scope" "/sys/fs/cgroup/docker/$id"; do
		if [[ -r "$dir/memory.peak" ]]; then
			printf '%s' "$dir"
			return 0
		fi
	done
}

# infra_checks FILE — test 12's infrastructure half, as JSON for the runner.
infra_checks() {
	local file="$1" edge chat embed
	edge='{"ok": null, "note": "edge is not running"}'
	if service_running edge; then
		if compose exec -T edge wget -q -T 3 -O /dev/null http://ai-chat:8080/health >/dev/null 2>&1; then
			edge='{"ok": false, "note": "the edge reached ai-chat"}'
		else
			edge='{"ok": true, "note": "the edge cannot resolve or reach ai-chat"}'
		fi
	fi
	chat="$(docker inspect "$(service_container ai-chat)")"
	embed="$(docker inspect "$(service_container ai-embed)")"
	jq -n --argjson edge "$edge" --argjson chat "$chat" --argjson embed "$embed" '
		def ports: (.[0].HostConfig.PortBindings // {}) | length == 0;
		def nets: .[0].NetworkSettings.Networks | keys;
		def hardened: .[0].Config.User == "10003:10003" and .[0].HostConfig.ReadonlyRootfs
			and .[0].HostConfig.Memory > 0 and .[0].HostConfig.Memory == .[0].HostConfig.MemorySwap;
		{
			unreachable_from_edge: $edge,
			no_published_ports: { ok: (($chat | ports) and ($embed | ports)) },
			internal_network_only: { ok: ((($chat | nets) + ($embed | nets)) | all(endswith("_data_net"))),
			                         networks: (($chat | nets) + ($embed | nets) | unique) },
			non_root_read_only_no_swap: { ok: (($chat | hardened) and ($embed | hardened)) }
		}' >"$file"
}

cmd_eval() {
	local chat="${1:-}" embed="${2:-}"
	[[ -n "$chat" && -n "$embed" ]] || die "Usage: ai-models.sh eval CHAT EMBED [--dataset DIR] [--out DIR] [-- RUNNER ARGS]"
	shift 2
	local dataset="$EVAL_STATE/dataset" out="" runner=()
	while (($# > 0)); do
		case "$1" in
		--dataset) dataset="${2:?}"; shift 2 ;;
		--out) out="${2:?}"; shift 2 ;;
		--) shift; runner=("$@"); break ;;
		*) die "Unknown option $1 (runner options go after --)" ;;
		esac
	done
	[[ -f "$dataset/manifest.json" ]] || die "No dataset in $dataset. Run: infra/scripts/ai-models.sh dataset"
	dataset="$(cd "$dataset" && pwd)"
	out="${out:-$EVAL_STATE/reports/$(date -u +%Y%m%dT%H%M%SZ)-$chat+$embed}"
	mkdir -p "$out"
	out="$(cd "$out" && pwd)"

	step "Evaluating $chat + $embed"
	links "$chat" "$embed"
	# A fresh container per run, so memory.peak covers this run only. Autoload on
	# for the run: the evaluation's requests load the pair (the app's owner switch
	# is not involved); the runtimes are stopped again at the end.
	AI_RUNTIME_AUTOLOAD=1 AI_CHAT_MODEL="$chat" AI_EMBED_MODEL="$embed" \
		compose --profile ai up -d --force-recreate ai-chat ai-embed
	wait_healthy ai-embed 300
	wait_healthy ai-chat 900

	local logs=() pids=() mounts=() memory=() service dir
	for service in ai-chat ai-embed; do
		compose logs --no-color --no-log-prefix -f "$service" >"$out/runtime-$service.log" 2>&1 &
		pids+=("$!")
		logs+=(--runtime-log "/out/runtime-$service.log")
		dir="$(cgroup_dir "$service")"
		if [[ -n "$dir" ]]; then
			mounts+=(-v "$dir:/run/cgroup/$service:ro")
			memory+=(--memory "$service=/run/cgroup/$service")
		else
			warn "No readable cgroup v2 memory.peak for $service: peak RAM will not be measured."
		fi
	done
	trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
	infra_checks "$out/infra-checks.json"

	local status=0
	AI_CHAT_MODEL="$chat" AI_EMBED_MODEL="$embed" tools run --rm --no-deps -T \
		--user "$(id -u):$(id -g)" -v "$dataset:/data:ro" -v "$out:/out" "${mounts[@]}" \
		ai-eval run --dataset /data --out /out --label "$chat + $embed" \
		"${memory[@]}" "${logs[@]}" --infra-checks /out/infra-checks.json "${runner[@]}" || status=$?
	kill "${pids[@]}" 2>/dev/null || true
	trap - EXIT

	if [[ -f "$out/report.md" ]]; then
		ok "report: $out/report.md"
	fi
	# The server is shared: give the pair's memory back as soon as the run is over.
	cmd_down
	info "To make the winner the active pair: ai-models.sh use CHAT EMBED && ai-models.sh up"
	return "$status"
}

# ── main ──────────────────────────────────────────────────────────────

command="${1:-}"
[[ -n "$command" ]] || {
	usage
	exit 2
}
shift
case "$command" in
list) cmd_list ;;
-h | --help | help) usage ;;
*)
	need_docker
	need jq
	[[ -f "$ENV_FILE" ]] || die "$ENV_FILE does not exist. Run infra/scripts/bootstrap.sh first."
	resolve_mode ""
	case "$command" in
	fetch) cmd_fetch "$@" ;;
	use) cmd_use "$@" ;;
	status) cmd_status ;;
	verify) cmd_verify ;;
	remove) cmd_remove "$@" ;;
	up) cmd_up "$@" ;;
	down) cmd_down ;;
	dataset) cmd_dataset "$@" ;;
	eval) cmd_eval "$@" ;;
	*)
		usage >&2
		die "Unknown command: $command"
		;;
	esac
	;;
esac
