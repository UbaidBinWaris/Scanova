#!/usr/bin/env bash
# Checks every library in the Android app's release classpath against the OSV
# vulnerability database (osv.dev). Gradle has no lockfile for the generic
# scanners, so the classpath is resolved here and sent as one batch query.
#
#   tools/security/android-deps-osv.sh        (from the repository root)
#
# Exits 1 when a library has a known vulnerability. Only package names and
# versions leave the machine, never code. Accept a finding by fixing it (a
# constraint in apps/android/app/build.gradle.kts), not by silencing it.
set -euo pipefail

cd "$(dirname "$0")/../../apps/android"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath >"$work/tree.txt"
# "group:artifact:1.0 -> 1.2" means 1.2 is what ships.
grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][^ ,()*]*( -> [0-9][^ ()*]*)?' "$work/tree.txt" |
	sed -E 's/:([^:]+) -> ([^ ]+)$/:\2/' | sort -u >"$work/coordinates.txt"
count="$(wc -l <"$work/coordinates.txt")"
((count > 50)) || {
	echo "only $count libraries resolved: the dependency report did not parse" >&2
	exit 2
}

jq -Rn '{queries: [inputs | split(":") | {package: {ecosystem: "Maven", name: (.[0] + ":" + .[1])}, version: .[2]}]}' \
	"$work/coordinates.txt" >"$work/query.json"
curl -fsS --retry 3 --max-time 120 -H 'content-type: application/json' \
	--data @"$work/query.json" https://api.osv.dev/v1/querybatch >"$work/answer.json"

paste -d' ' "$work/coordinates.txt" <(jq -r '.results[] | [.vulns[]?.id] | join(",")' "$work/answer.json") |
	awk 'NF > 1' >"$work/vulnerable.txt"
echo "checked $count Android libraries against osv.dev"
if [[ -s "$work/vulnerable.txt" ]]; then
	echo "known vulnerabilities (details: https://osv.dev/vulnerability/<id>):"
	sed 's/^/  /' "$work/vulnerable.txt"
	exit 1
fi
echo "no known vulnerabilities"
