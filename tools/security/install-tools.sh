#!/usr/bin/env bash
# Installs the security scanners used by .github/workflows/security.yml into a
# directory (default: ./.security-tools/bin), pinned by version AND SHA-256.
#
#   tools/security/install-tools.sh [DIR] [TOOL ...]
#
# Release binaries straight from each project's GitHub releases: no third-party
# wrapper actions, and nothing runs unless its checksum matches. To upgrade a
# tool, change its version, URL and checksum together (sha256 from the release's
# own checksum file, or GitHub's asset digest when there is none).
set -euo pipefail

DIR="${1:-.security-tools}"
shift || true
BIN="$DIR/bin"
mkdir -p "$BIN" "$DIR/download"

# name|version|url|sha256|how to unpack
TOOLS=(
	"gitleaks|8.30.1|https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz|551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb|tar:gitleaks"
	"trivy|0.76.2|https://github.com/aquasecurity/trivy/releases/download/v0.76.2/trivy_0.76.2_Linux-64bit.tar.gz|bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf|tar:trivy"
	"osv-scanner|2.7.1|https://github.com/google/osv-scanner/releases/download/v2.7.1/osv-scanner_linux_amd64|fb7dab19a8bd9bbe5c3189f09d4b7ee1820c9553a23bac202648a56efd2f6ee8|bin"
	"actionlint|1.7.12|https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz|8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8|tar:actionlint"
	"shellcheck|0.11.0|https://github.com/koalaman/shellcheck/releases/download/v0.11.0/shellcheck-v0.11.0.linux.x86_64.tar.xz|8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198|txz:shellcheck-v0.11.0/shellcheck"
	"hadolint|2.15.1|https://github.com/hadolint/hadolint/releases/download/v2.15.1/hadolint-linux-x86_64|c7187db94eeeeca956519a6af171adc31453941a1e777961f6e680f697c8c507|bin"
)

wanted() {
	(($# == 0)) && return 0
	local name="$1"
	shift
	for want in "${SELECTED[@]}"; do [[ "$want" == "$name" ]] && return 0; done
	return 1
}

SELECTED=("$@")
for entry in "${TOOLS[@]}"; do
	IFS='|' read -r name version url sha unpack <<<"$entry"
	if ((${#SELECTED[@]} > 0)) && ! wanted "$name" "${SELECTED[@]}"; then
		continue
	fi
	file="$DIR/download/$(basename "$url")"
	curl -fsSL --retry 3 --max-time 300 -o "$file" "$url"
	echo "$sha  $file" | sha256sum --check --quiet - || {
		echo "checksum mismatch for $name $version: refusing to install" >&2
		exit 1
	}
	case "$unpack" in
	bin) install -m 755 "$file" "$BIN/$name" ;;
	tar:*) tar -xzf "$file" -C "$BIN" "${unpack#tar:}" ;;
	txz:*) tar -xJf "$file" -C "$DIR/download" "${unpack#txz:}" && install -m 755 "$DIR/download/${unpack#txz:}" "$BIN/$name" ;;
	esac
	echo "installed $name $version"
done
echo "$BIN"
