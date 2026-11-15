#!/usr/bin/env bash
# Scanova — publishes a signed Android build for the download site and the
# in-app updater. Started by deploy-gate (`publish-apk ...`); stdin is the APK.
#
#   publish-apk.sh <versionCode> <versionName> <apk-sha256> <cert-sha256> <notes-base64>
#
# Refuses a build that is older than the published one, a different file under
# an already-published version code, and a build signed with another key (it
# could never update the installed app). Re-publishing the same build changes
# nothing. Keeps the newest few APKs; writes latest.json last, atomically.
set -euo pipefail

code="$1" name="$2" sha="$3" cert="$4" notes_b64="$5"
DIR=/opt/scanova/shared/state/downloads/android
PACKAGE=app.scanova.android
KEEP=5

fail() {
	printf 'publish-apk: %s\n' "$*" >&2
	exit 1
}

install -d -m 755 "$DIR"
tmp="$(mktemp -p "$DIR" .upload-XXXXXX)"
trap 'rm -f "$tmp" "$DIR/.latest.json.tmp"' EXIT
cat >"$tmp"

size="$(stat -c %s "$tmp")"
((size > 0)) || fail "empty upload"
[[ "$(head -c 4 "$tmp" | od -An -tx1 | tr -d ' \n')" == "504b0304" ]] || fail "not an APK (zip) file"
[[ "$(sha256sum "$tmp" | cut -d' ' -f1)" == "$sha" ]] || fail "SHA-256 does not match: the upload was damaged"

latest="$DIR/latest.json"
min_supported=0
if [[ -f "$latest" ]]; then
	current_code="$(jq -r '.versionCode' "$latest")"
	current_sha="$(jq -r '.sha256' "$latest")"
	current_cert="$(jq -r '.signingCertSha256' "$latest")"
	min_supported="$(jq -r '.minSupportedVersionCode // 0' "$latest")"
	if ((code == current_code)); then
		[[ "$sha" == "$current_sha" ]] || fail "version code $code is already published with different content"
		echo "already published: $name ($code)"
		exit 0
	fi
	((code > current_code)) || fail "version code $code is older than the published $current_code"
	[[ "$cert" == "$current_cert" ]] ||
		fail "signed with a different key than the published build: installed apps could not update to it"
fi

file="scanova-$name-$code.apk"
mv -f "$tmp" "$DIR/$file"
chmod 644 "$DIR/$file"

notes_json="$(printf '%s' "$notes_b64" | base64 -d 2>/dev/null | head -c 4000 |
	jq -R -s 'split("\n") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0)) | .[0:12]')"
jq -n \
	--arg package "$PACKAGE" --argjson code "$code" --arg name "$name" --arg file "$file" \
	--argjson size "$size" --arg sha "$sha" --arg cert "$cert" --argjson min "$min_supported" \
	--arg published "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" --argjson notes "$notes_json" \
	'{schemaVersion: 1, packageName: $package, versionCode: $code, versionName: $name, file: $file,
	  sizeBytes: $size, sha256: $sha, signingCertSha256: $cert, minSupportedVersionCode: $min,
	  publishedAt: $published, notes: $notes}' >"$DIR/.latest.json.tmp"
chmod 644 "$DIR/.latest.json.tmp"
# The stable download link for people; the updater uses the versioned name.
ln -sfn "$file" "$DIR/scanova.apk"
mv -f "$DIR/.latest.json.tmp" "$latest"

# grep exits 1 when nothing is left to prune; that is not an error.
{ ls -1t "$DIR"/scanova-*.apk 2>/dev/null | grep -vx "$DIR/$file" | tail -n +"$KEEP" | xargs -r rm -f; } || true
echo "published $name ($code): $file, $size bytes"
