#!/usr/bin/env bash
# Scanova smoke test — proves the infrastructure works and is locked down,
# without the application: PostgreSQL (TLS, roles, database isolation), Garage
# (S3 round trip), OpenBao (what each AppRole can and cannot do) and the edge
# (CA certificate, /internal blocked).
#
# Everything is checked from inside the Compose network except the edge, which
# is checked from this host as a phone would reach it.
#
# Side effects: one new version of the member user-type key (rotated by the
# worker AppRole to prove it can, and to exercise re-wrap). Nothing else is
# left behind. Use --no-rotate on a system you do not want to touch.

set -Euo pipefail
umask 077

# shellcheck source=infra/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MODE_FLAG=""
DO_ROTATE=1
PASSED=0
FAILED=0
SKIPPED=0
OUT=""
RC=0

usage() {
	cat <<'EOF'
Usage: infra/scripts/smoke-test.sh [--no-rotate] [--dev|--prod]

  --no-rotate   Skip the checks that rotate a Transit key.
  --dev|--prod  Override the stack mode recorded by bootstrap.sh.
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
	--no-rotate) DO_ROTATE=0 ;;
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

# ── Test helpers ──────────────────────────────────────────────────────

pass() {
	PASSED=$((PASSED + 1))
	printf '   %sPASS%s  %s\n' "$C_GREEN" "$C_RESET" "$1"
}

fail() {
	FAILED=$((FAILED + 1))
	printf '   %sFAIL%s  %s\n' "$C_RED" "$C_RESET" "$1"
	if [[ -n "${2:-}" ]]; then
		printf '%s\n' "$2" | tail -n 4 | sed 's/^/         /'
	fi
}

skip() {
	SKIPPED=$((SKIPPED + 1))
	printf '   %sSKIP%s  %s\n' "$C_YELLOW" "$C_RESET" "$1"
}

# run CMD… — captures combined output in OUT and the exit status in RC.
run() {
	OUT="$("$@" 2>&1)"
	RC=$?
}

# expect_ok DESCRIPTION CMD… — the command must succeed.
expect_ok() {
	local description="$1"
	shift
	run "$@"
	if ((RC == 0)); then pass "$description"; else fail "$description" "$OUT"; fi
}

# expect_equal DESCRIPTION EXPECTED CMD… — must succeed and print EXPECTED.
expect_equal() {
	local description="$1" expected="$2"
	shift 2
	run "$@"
	if ((RC == 0)) && [[ "$OUT" == "$expected" ]]; then
		pass "$description"
	else
		fail "$description" "expected: $expected"$'\n'"got (exit $RC): $OUT"
	fi
}

# expect_refused DESCRIPTION PATTERN CMD… — must fail, with output matching
# PATTERN (so a refusal is told apart from an unrelated error).
expect_refused() {
	local description="$1" pattern="$2"
	shift 2
	run "$@"
	if ((RC != 0)) && grep -Eqi -- "$pattern" <<<"$OUT"; then
		pass "$description"
	elif ((RC == 0)); then
		fail "$description" "was allowed, but must be refused"
	else
		fail "$description" "failed, but not with /$pattern/: $OUT"
	fi
}

# ── Service access helpers ────────────────────────────────────────────

# pg_sql TARGET SSLMODE DATABASE USER PASSWORD SQL
pg_sql() {
	PGPASSWORD="$5" pg_as "$1" "$2" "$3" "$4" -c "$6"
}

# bao_t TOKEN ARGS… — `bao` with a token.
bao_t() {
	local token="$1"
	shift
	BAO_TOKEN="$token" bao_cli "$@"
}

# bao_post TOKEN PATH JSON — write with a JSON body on stdin, JSON reply.
bao_post() {
	printf '%s' "$3" | BAO_TOKEN="$1" bao_cli write -format=json "$2" -
}

# bao_capabilities TOKEN PATH — the token's own capabilities on PATH.
bao_capabilities() {
	jq -n --arg p "$2" '{paths: [$p]}' | BAO_TOKEN="$1" bao_cli write -format=json sys/capabilities-self - |
		jq -r --arg p "$2" '.data[$p] | join(",")'
}

# probe CURL_ARGS… — curl inside the data network; curl config on stdin.
probe() {
	compose --progress quiet --profile tools run --rm -T probe -sS "$@" 2>&1
}

b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

# ── Sections ──────────────────────────────────────────────────────────

test_services() {
	step "Services"
	local service
	local services=(edge postgres openbao)
	s3_external || services+=(garage)
	for service in "${services[@]}"; do
		expect_equal "$service is healthy" "healthy" service_health "$service"
	done
}

test_postgres() {
	step "PostgreSQL"
	local db keys_db app app_pw mig mig_pw kapp kapp_pw kmig kmig_pw location cross
	db="$(env_require POSTGRES_DB)"
	app="$(env_require POSTGRES_APP_USER)"
	app_pw="$(env_require POSTGRES_APP_PASSWORD)"
	mig="$(env_require POSTGRES_MIGRATOR_USER)"
	mig_pw="$(env_require POSTGRES_MIGRATOR_PASSWORD)"
	keys_db="$(env_require KEYS_POSTGRES_DB)"
	kapp="$(env_require KEYS_POSTGRES_APP_USER)"
	kapp_pw="$(env_require KEYS_POSTGRES_APP_PASSWORD)"
	kmig="$(env_require KEYS_POSTGRES_MIGRATOR_USER)"
	kmig_pw="$(env_require KEYS_POSTGRES_MIGRATOR_PASSWORD)"
	location="$(keys_db_location)"
	info "main database: bundled postgres service; key database: $location"

	local tls_sql="select current_user || ' ' || current_database() || ' ' || version from pg_stat_ssl where ssl and pid = pg_backend_pid()"

	expect_equal "main runtime role connects to '$db' over TLS 1.3, server certificate verified" \
		"$app $db TLSv1.3" pg_sql main verify-full "$db" "$app" "$app_pw" "$tls_sql"
	expect_equal "main migrator connects to '$db' over TLS 1.3" \
		"$mig $db TLSv1.3" pg_sql main verify-full "$db" "$mig" "$mig_pw" "$tls_sql"
	expect_equal "key runtime role connects to '$keys_db' over TLS 1.3, server certificate verified" \
		"$kapp $keys_db TLSv1.3" pg_sql keys verify-full "$keys_db" "$kapp" "$kapp_pw" "$tls_sql"
	expect_equal "key migrator connects to '$keys_db' over TLS 1.3" \
		"$kmig $keys_db TLSv1.3" pg_sql keys verify-full "$keys_db" "$kmig" "$kmig_pw" "$tls_sql"

	expect_refused "a non-TLS connection to '$db' is refused" "no encryption" \
		pg_sql main disable "$db" "$app" "$app_pw" "select 1"
	expect_refused "a non-TLS connection to '$keys_db' is refused" "no encryption" \
		pg_sql keys disable "$keys_db" "$kapp" "$kapp_pw" "select 1"
	expect_refused "a wrong password is refused" "password authentication failed" \
		pg_sql main verify-full "$db" "$app" "not-the-password" "select 1"
	expect_refused "the superuser cannot log in over the network" "pg_hba.conf rejects" \
		pg_sql main verify-full postgres postgres "$(env_require POSTGRES_SUPERUSER_PASSWORD)" "select 1"

	# Database isolation (§5.2): each role enters its own database only. On a
	# shared instance that is the CONNECT privilege; on a separate key instance
	# the foreign roles do not exist there at all.
	if [[ "$location" == "bundled" ]]; then
		cross="permission denied for database"
	else
		cross="permission denied for database|authentication failed|does not exist"
	fi
	expect_refused "main runtime role is refused by the key database" "$cross" \
		pg_sql keys verify-full "$keys_db" "$app" "$app_pw" "select 1"
	expect_refused "main migrator is refused by the key database" "$cross" \
		pg_sql keys verify-full "$keys_db" "$mig" "$mig_pw" "select 1"
	expect_refused "key runtime role is refused by the main database" "permission denied for database" \
		pg_sql main verify-full "$db" "$kapp" "$kapp_pw" "select 1"
	expect_refused "key migrator is refused by the main database" "permission denied for database" \
		pg_sql main verify-full "$db" "$kmig" "$kmig_pw" "select 1"
	expect_refused "main runtime role is refused by the maintenance database" "permission denied for database" \
		pg_sql main verify-full postgres "$app" "$app_pw" "select 1"

	# Schema rights: migrators own `public`, runtime roles cannot create in it.
	local ddl="create table public.scanova_smoke_ddl (id int)"
	expect_refused "main runtime role cannot run DDL" "permission denied for schema public" \
		pg_sql main verify-full "$db" "$app" "$app_pw" "$ddl"
	expect_refused "key runtime role cannot run DDL" "permission denied for schema public" \
		pg_sql keys verify-full "$keys_db" "$kapp" "$kapp_pw" "$ddl"
	expect_ok "main migrator can run DDL (rolled back)" \
		pg_sql main verify-full "$db" "$mig" "$mig_pw" "begin; $ddl; rollback;"
	expect_ok "key migrator can run DDL (rolled back)" \
		pg_sql keys verify-full "$keys_db" "$kmig" "$kmig_pw" "begin; $ddl; rollback;"
	expect_equal "main migrator owns schema public of '$db'" "$mig" \
		pg_sql main verify-full "$db" "$mig" "$mig_pw" "select nspowner::regrole from pg_namespace where nspname = 'public'"
	expect_equal "key migrator owns schema public of '$keys_db'" "$kmig" \
		pg_sql keys verify-full "$keys_db" "$kmig" "$kmig_pw" "select nspowner::regrole from pg_namespace where nspname = 'public'"
}

probe_no_input() { probe "$@" </dev/null; }

test_network() {
	step "Network exposure"
	local service ports expected
	expect_refused "a container on data_net has no route out (internal network)" 'curl: \((6|7|28)\)' \
		probe_no_input --max-time 6 -o /dev/null http://1.1.1.1/
	local published=(postgres openbao)
	s3_external || published+=(garage)
	for service in "${published[@]}"; do
		ports="$(docker port "$(service_container "$service")" 2>/dev/null | sed 's/^.* -> //' | sort -u | tr '\n' ' ')"
		ports="${ports% }"
		if [[ "$STACK_MODE" == "dev" ]]; then
			case "$service" in
			postgres) expected="127.0.0.1:55432" ;;
			garage) expected="127.0.0.1:53900" ;;
			openbao) expected="127.0.0.1:58200" ;;
			esac
			if [[ "$ports" == "$expected" ]]; then
				pass "$service is published on loopback only ($ports)"
			else
				fail "$service must be published on $expected only" "published: ${ports:-nothing}"
			fi
		elif [[ -z "$ports" ]]; then
			pass "$service publishes no host port"
		else
			fail "$service must not publish a host port" "published: $ports"
		fi
	done
}

test_garage() {
	step "Garage (S3)"
	local key_id secret region bucket object body url auth code got
	key_id="$(env_require S3_ACCESS_KEY_ID)"
	secret="$(env_require S3_SECRET_ACCESS_KEY)"
	region="$(env_require S3_REGION)"
	bucket="$(env_require S3_BUCKET)"
	object="scanova-smoke/$(rand_hex 8)"
	body="scanova smoke test $(rand_hex 16)"
	local endpoint
	endpoint="$(env_get S3_ENDPOINT_INTERNAL)"
	endpoint="${endpoint:-http://garage:3900}"
	url="$endpoint/$bucket/$object"
	auth="user = \"$key_id:$secret\""$'\n'"aws-sigv4 = \"aws:amz:$region:s3\""

	code="$(probe -K - -o /dev/null -w '%{http_code}' -X PUT --data-binary "$body" "$url" <<<"$auth")"
	if [[ "$code" == "200" ]]; then pass "PUT object into '$bucket'"; else fail "PUT object into '$bucket'" "HTTP $code"; fi

	got="$(probe -K - "$url" <<<"$auth")"
	if [[ "$got" == "$body" ]]; then pass "GET returns the same bytes"; else fail "GET returns the same bytes" "$got"; fi

	code="$(probe -o /dev/null -w '%{http_code}' "$url" </dev/null)"
	if [[ "$code" == "403" ]]; then pass "GET without credentials is refused (403)"; else fail "GET without credentials must be refused" "HTTP $code"; fi

	code="$(probe -K - -o /dev/null -w '%{http_code}' "$url" <<<"user = \"$key_id:$(rand_hex 32)\""$'\n'"aws-sigv4 = \"aws:amz:$region:s3\"")"
	if [[ "$code" == "403" ]]; then pass "GET with a wrong secret key is refused (403)"; else fail "GET with a wrong secret key must be refused" "HTTP $code"; fi

	code="$(probe -K - -o /dev/null -w '%{http_code}' -X PUT "$endpoint/scanova-smoke-$(rand_hex 4)" <<<"$auth")"
	if [[ "$code" == "403" ]]; then pass "the access key cannot create a bucket (403)"; else fail "the access key must not create buckets" "HTTP $code"; fi

	code="$(probe -K - -o /dev/null -w '%{http_code}' -X DELETE "$url" <<<"$auth")"
	if [[ "$code" == "204" ]]; then pass "DELETE object"; else fail "DELETE object" "HTTP $code"; fi

	code="$(probe -K - -o /dev/null -w '%{http_code}' "$url" <<<"$auth")"
	if [[ "$code" == "404" ]]; then pass "the object is gone (404)"; else fail "the object must be gone" "HTTP $code"; fi
}

test_openbao() {
	step "OpenBao"
	local mount utk_admin utk_member sys jwt api worker operator out
	mount="$(env_require BAO_TRANSIT_MOUNT)"
	utk_admin="$(env_require BAO_KEY_UTK_ADMIN)"
	utk_member="$(env_require BAO_KEY_UTK_MEMBER)"
	sys="$(env_require BAO_KEY_SYSTEM)"
	jwt="$(env_require BAO_KEY_JWT)"

	# Transport: TLS only, certificate valid for the in-network name.
	out="$(probe -o /dev/null -w '%{http_code}' --cacert "$CA_IN_CONTAINER" https://openbao:8200/v1/sys/health </dev/null)"
	if [[ "$out" == "200" ]]; then pass "https://openbao:8200 answers, certificate verified against the internal CA"; else fail "OpenBao over verified TLS" "HTTP $out"; fi
	out="$(probe -o /dev/null -w '%{http_code}' http://openbao:8200/v1/sys/health </dev/null)"
	if [[ "$out" == "400" ]]; then pass "plain HTTP to OpenBao is rejected"; else fail "plain HTTP to OpenBao must be rejected" "HTTP $out"; fi

	expect_refused "an unauthenticated read of a key is refused" "permission denied|missing client token" \
		bao_t "" read "$mount/keys/$utk_member"

	# ── api AppRole ──
	if ! api="$(bao_approle_login "$(env_require BAO_API_ROLE_ID)" "$(env_require BAO_API_SECRET_ID)")"; then
		fail "api AppRole logs in"
		return
	fi
	pass "api AppRole logs in"
	expect_equal "api token carries only policy $BAO_POLICY_API" "$BAO_POLICY_API" token_policies "$api"

	# Key properties the contract requires (§5.2, §8).
	local utk_seconds jwt_seconds name
	utk_seconds=$(($(env_require KEY_ROTATION_UTK_DAYS) * 86400))
	jwt_seconds=$(($(env_require KEY_ROTATION_JWT_DAYS) * 86400))
	for name in "$utk_admin" "$utk_member"; do
		expect_equal "$name: aes256-gcm96, derived, not exportable, not deletable, auto-rotate ${utk_seconds}s" \
			"aes256-gcm96 true false false false $utk_seconds" key_properties "$api" "$mount/keys/$name"
	done
	expect_equal "$sys: aes256-gcm96, not derived, not exportable, not deletable" \
		"aes256-gcm96 false false false false 0" key_properties "$api" "$mount/keys/$sys"
	expect_equal "$jwt: ed25519, not exportable, not deletable, auto-rotate ${jwt_seconds}s" \
		"ed25519 false false false false $jwt_seconds" key_properties "$api" "$mount/keys/$jwt"

	# Data key under a derived key, bound to a context.
	local ctx_a ctx_b datakey dk_plain dk_wrapped plain
	ctx_a="$(b64 "user-$(rand_hex 8)")"
	ctx_b="$(b64 "user-$(rand_hex 8)")"
	if datakey="$(bao_post "$api" "$mount/datakey/plaintext/$utk_member" "{\"context\":\"$ctx_a\"}" 2>&1)" &&
		dk_plain="$(jq -r .data.plaintext <<<"$datakey")" && dk_wrapped="$(jq -r .data.ciphertext <<<"$datakey")" &&
		[[ "$(printf '%s' "$dk_plain" | base64 -d | wc -c)" -eq 32 && "$dk_wrapped" == vault:v* ]]; then
		pass "api generates a 256-bit data key under $utk_member with a context"
		expect_equal "the wrapped data key unwraps to the same key with that context" "$dk_plain" \
			decrypt_field "$api" "$mount/decrypt/$utk_member" "$dk_wrapped" "$ctx_a"
	else
		fail "api generates a data key under $utk_member" "$datakey"
	fi
	dk_plain=""

	# Encrypt / decrypt with context.
	local ct_a ct_b
	plain="$(b64 "$(rand_hex 32)")"
	ct_a="$(bao_post "$api" "$mount/encrypt/$utk_member" "{\"plaintext\":\"$plain\",\"context\":\"$ctx_a\"}" 2>&1 | jq -r '.data.ciphertext' 2>/dev/null)"
	ct_b="$(bao_post "$api" "$mount/encrypt/$utk_member" "{\"plaintext\":\"$plain\",\"context\":\"$ctx_b\"}" 2>&1 | jq -r '.data.ciphertext' 2>/dev/null)"
	if [[ "$ct_a" == vault:v* && "$ct_b" == vault:v* ]]; then pass "api encrypts under $utk_member with a context"; else fail "api encrypts under $utk_member with a context" "$ct_a"; fi
	expect_equal "api decrypts with the same context" "$plain" decrypt_field "$api" "$mount/decrypt/$utk_member" "$ct_a" "$ctx_a"
	expect_refused "decrypting with another user's context fails" "message authentication failed" \
		decrypt_field "$api" "$mount/decrypt/$utk_member" "$ct_a" "$ctx_b"
	expect_refused "encrypting under a derived key without a context fails" "context" \
		bao_post "$api" "$mount/encrypt/$utk_member" "{\"plaintext\":\"$plain\"}"
	expect_refused "the admin user-type key cannot unwrap a member's ciphertext" "message authentication failed|invalid ciphertext" \
		decrypt_field "$api" "$mount/decrypt/$utk_admin" "$ct_a" "$ctx_a"
	expect_refused "encrypting with an unknown key name does not create a key" "permission denied" \
		bao_post "$api" "$mount/encrypt/scanova-smoke-unknown" "{\"plaintext\":\"$plain\"}"

	local ct_sys
	ct_sys="$(bao_post "$api" "$mount/encrypt/$sys" "{\"plaintext\":\"$plain\"}" 2>&1 | jq -r '.data.ciphertext' 2>/dev/null)"
	expect_equal "api encrypts and decrypts under $sys" "$plain" decrypt_field "$api" "$mount/decrypt/$sys" "$ct_sys" ""

	# Sign, and verify the signature outside the vault with the public key.
	test_signature "$api" "$mount" "$jwt"

	# What the api AppRole must not be able to do.
	for name in "$utk_admin" "$utk_member" "$sys" "$jwt"; do
		expect_refused "api cannot rotate $name" "permission denied" bao_t "$api" write -f "$mount/keys/$name/rotate"
		expect_refused "api cannot change the configuration of $name" "permission denied" \
			bao_t "$api" write "$mount/keys/$name/config" min_decryption_version=1
		expect_refused "api cannot delete $name" "permission denied" bao_t "$api" delete "$mount/keys/$name"
		expect_refused "api cannot back up $name" "permission denied" bao_t "$api" read "$mount/backup/$name"
		expect_equal "api has no capability to soft-delete $name" "deny" bao_capabilities "$api" "$mount/keys/$name/soft-delete"
	done
	for name in "$utk_admin" "$utk_member" "$sys"; do
		expect_refused "api cannot export $name" "permission denied" bao_t "$api" read "$mount/export/encryption-key/$name"
	done
	expect_refused "api cannot export $jwt" "permission denied" bao_t "$api" read "$mount/export/signing-key/$jwt"
	expect_refused "api cannot create a key" "permission denied" bao_t "$api" write -f "$mount/keys/scanova-smoke-new"
	expect_refused "api cannot read policies" "permission denied" bao_t "$api" policy read "$BAO_POLICY_API"

	# ── worker AppRole ──
	if ! worker="$(bao_approle_login "$(env_require BAO_WORKER_ROLE_ID)" "$(env_require BAO_WORKER_SECRET_ID)")"; then
		fail "worker AppRole logs in"
		bao_revoke_self "$api"
		return
	fi
	pass "worker AppRole logs in"

	if ((DO_ROTATE)); then
		local before after rewrapped
		before="$(bao_t "$worker" read -field=latest_version "$mount/keys/$utk_member")"
		expect_ok "worker rotates $utk_member" bao_t "$worker" write -f "$mount/keys/$utk_member/rotate"
		after="$(bao_t "$worker" read -field=latest_version "$mount/keys/$utk_member")"
		if [[ "$after" -eq $((before + 1)) ]]; then pass "$utk_member moved from version $before to $after"; else fail "$utk_member must gain one version" "before $before, after $after"; fi

		# Batched re-wrap, each item with its own context, by the api AppRole.
		rewrapped="$(bao_post "$api" "$mount/rewrap/$utk_member" \
			"{\"batch_input\":[{\"ciphertext\":\"$ct_a\",\"context\":\"$ctx_a\"},{\"ciphertext\":\"$ct_b\",\"context\":\"$ctx_b\"}]}" 2>&1)"
		if [[ "$(jq -r '[.data.batch_results[].ciphertext | split(":")[1]] | join(",")' <<<"$rewrapped" 2>/dev/null)" == "v$after,v$after" ]]; then
			pass "api re-wraps a batch to version $after inside the vault"
			expect_equal "re-wrapped ciphertext decrypts to the original with its context" "$plain" \
				decrypt_field "$api" "$mount/decrypt/$utk_member" "$(jq -r '.data.batch_results[1].ciphertext' <<<"$rewrapped")" "$ctx_b"
		else
			fail "api re-wraps a batch to version $after" "$rewrapped"
		fi
		expect_equal "ciphertext of version $before still decrypts (old versions stay valid until retired)" "$plain" \
			decrypt_field "$api" "$mount/decrypt/$utk_member" "$ct_a" "$ctx_a"
	else
		skip "worker rotates $utk_member, api re-wraps a batch (--no-rotate)"
		expect_equal "worker has the capability to rotate $utk_member" "update" bao_capabilities "$worker" "$mount/keys/$utk_member/rotate"
	fi

	for name in "$utk_admin" "$utk_member" "$sys" "$jwt"; do
		# Writing the current value changes nothing but proves the permission.
		expect_ok "worker may set min_decryption_version of $name" bao_t "$worker" write "$mount/keys/$name/config" \
			"min_decryption_version=$(bao_t "$worker" read -field=min_decryption_version "$mount/keys/$name")"
		expect_refused "worker cannot make $name deletable" "permission denied" \
			bao_t "$worker" write "$mount/keys/$name/config" deletion_allowed=false
		expect_refused "worker cannot make $name exportable" "permission denied" \
			bao_t "$worker" write "$mount/keys/$name/config" exportable=false
		expect_refused "worker cannot delete $name" "permission denied" bao_t "$worker" delete "$mount/keys/$name"
		expect_refused "worker cannot back up $name" "permission denied" bao_t "$worker" read "$mount/backup/$name"
		expect_refused "worker cannot trim $name" "permission denied" bao_t "$worker" write "$mount/keys/$name/trim" min_available_version=1
		expect_equal "worker has no capability to soft-delete $name" "deny" bao_capabilities "$worker" "$mount/keys/$name/soft-delete"
	done
	for name in "$utk_admin" "$utk_member" "$sys"; do
		expect_refused "worker cannot export $name" "permission denied" bao_t "$worker" read "$mount/export/encryption-key/$name"
	done
	expect_refused "worker cannot export $jwt" "permission denied" bao_t "$worker" read "$mount/export/signing-key/$jwt"
	expect_refused "worker cannot create a key" "permission denied" bao_t "$worker" write -f "$mount/keys/scanova-smoke-new"

	# ── operator AppRole: useless without recovery key shares ──
	if [[ -s "$OPERATOR_FILE" ]]; then
		if operator="$(bao_approle_login "$(jq -r .role_id "$OPERATOR_FILE")" "$(jq -r .secret_id "$OPERATOR_FILE")")"; then
			expect_refused "the operator AppRole alone cannot read a key" "permission denied" bao_t "$operator" read "$mount/keys/$utk_member"
			expect_refused "the operator AppRole alone cannot decrypt" "permission denied" \
				decrypt_field "$operator" "$mount/decrypt/$utk_member" "$ct_a" "$ctx_a"
			bao_revoke_self "$operator"
		else
			fail "operator AppRole logs in"
		fi
	fi

	bao_revoke_self "$api"
	bao_revoke_self "$worker"
	expect_refused "a revoked token is refused" "permission denied" bao_t "$api" read "$mount/keys/$utk_member"
}

# token_policies TOKEN — the policies attached to a token, comma-separated.
token_policies() {
	bao_t "$1" token lookup -format=json | jq -r '.data.policies | join(",")'
}

# key_properties TOKEN PATH — "type derived exportable backup deletable auto_rotate_seconds"
key_properties() {
	bao_t "$1" read -format=json "$2" | jq -r '.data |
		"\(.type) \(.derived // false) \(.exportable) \(.allow_plaintext_backup) \(.deletion_allowed) \(.auto_rotate_period)"'
}

# decrypt_field TOKEN PATH CIPHERTEXT CONTEXT — prints the base64 plaintext.
decrypt_field() {
	local body
	if [[ -n "$4" ]]; then
		body="{\"ciphertext\":\"$3\",\"context\":\"$4\"}"
	else
		body="{\"ciphertext\":\"$3\"}"
	fi
	bao_post "$1" "$2" "$body" | jq -er '.data.plaintext'
}

test_signature() { # TOKEN MOUNT KEY
	local token="$1" mount="$2" key="$3" work message signed version signature public
	work="$(mktemp -d)"
	message="scanova.smoke.$(rand_hex 16)"
	printf '%s' "$message" >"$work/message"

	if ! signed="$(bao_post "$token" "$mount/sign/$key" "{\"input\":\"$(b64 "$message")\"}" 2>&1)" ||
		[[ "$(jq -r .data.signature <<<"$signed" 2>/dev/null)" != vault:v*:* ]]; then
		fail "api signs with $key" "$signed"
		rm -rf "$work"
		return
	fi
	pass "api signs with $key"
	signature="$(jq -r .data.signature <<<"$signed")"
	version="$(cut -d: -f2 <<<"$signature")"
	version="${version#v}"
	cut -d: -f3 <<<"$signature" | base64 -d >"$work/signature"

	public="$(bao_t "$token" read -format=json "$mount/keys/$key" | jq -r --arg v "$version" '.data.keys[$v].public_key')"
	# SubjectPublicKeyInfo header for Ed25519, then the raw 32-byte key.
	{
		printf '\x30\x2a\x30\x05\x06\x03\x2b\x65\x70\x03\x21\x00'
		printf '%s' "$public" | base64 -d
	} >"$work/public.der"

	if [[ "$(wc -c <"$work/signature")" -ne 64 ]]; then
		fail "the signature is a 64-byte Ed25519 signature"
	elif ! openssl pkeyutl -help 2>&1 | grep -q -- '-rawin'; then
		skip "signature verification outside the vault (this openssl has no -rawin)"
	else
		expect_ok "the signature verifies outside the vault with the public key of version $version" \
			openssl pkeyutl -verify -pubin -inkey "$work/public.der" -keyform DER -rawin -in "$work/message" -sigfile "$work/signature"
	fi
	rm -rf "$work"
}

test_edge() {
	step "Edge"
	local port mode base curl_tls=() code headers served
	port="$(env_require SCANOVA_HTTPS_PORT)"
	mode="$(env_require SCANOVA_TLS_MODE)"
	need curl

	if [[ "$mode" == "internal" ]]; then
		base="https://localhost:$port"
		curl_tls=(--cacert "$PKI_DIR/ca.crt")
	else
		base="$(env_require SCANOVA_PUBLIC_URL)"
		base="${base%/}"
	fi

	# edge_code PATH [CURL_ARGS…] — HTTP status and body size, path sent as is.
	edge_code() {
		local path="$1"
		shift
		curl -sS --max-time 15 "${curl_tls[@]}" --path-as-is -o /dev/null -w '%{http_code} %{size_download}' "$@" "$base$path" 2>&1
	}

	if [[ "$mode" == "internal" ]]; then
		served="$(mktemp)"
		code="$(curl -sS --max-time 15 "${curl_tls[@]}" -o "$served" -w '%{http_code}' "$base/.well-known/scanova/ca.crt" 2>&1)"
		if [[ "$code" == "200" ]]; then pass "TLS handshake verified against the internal CA (localhost)"; else fail "edge over verified TLS" "$code"; fi
		if [[ "$(openssl x509 -in "$served" -outform DER 2>/dev/null | openssl dgst -sha256 -r | cut -d' ' -f1)" == "$(ca_fingerprint)" ]]; then
			pass "/.well-known/scanova/ca.crt is the CA certificate with the pairing fingerprint"
		else
			fail "/.well-known/scanova/ca.crt must be the CA certificate"
		fi
		rm -f "$served"

		code="$(curl -sS --max-time 15 "${curl_tls[@]}" -o /dev/null -w '%{http_code}' "https://127.0.0.1:$port/.well-known/scanova/ca.crt" 2>&1)"
		if [[ "$code" == "200" ]]; then pass "connecting by IP address (no SNI) gets the same verified certificate"; else fail "edge by IP address" "$code"; fi
	else
		skip "internal-CA checks (SCANOVA_TLS_MODE=$mode)"
	fi

	# /internal/* is answered by the edge itself: 404 with an empty body. A
	# proxied request would carry the API's JSON envelope, or be a 502.
	local path
	for path in /internal /internal/ /internal/anything /internal/admin/v1/users /INTERNAL/anything \
		/api/v1/../../internal/anything /api/v1/..%2f..%2finternal/anything /api/v1/%2e%2e/%2e%2e/internal/anything \
		//internal/anything '/internal/anything?x=1' '/api/v1/..;/internal/anything' '/api/v1\..\..\internal\anything'; do
		code="$(edge_code "$path")"
		if [[ "$code" == "404 0" ]]; then pass "$path → 404, not proxied"; else fail "$path must be 404 from the edge" "$code"; fi
	done
	code="$(edge_code /internal/web/v1/auth/login -X POST -H 'X-Scanova-Service-Token: guess' -H 'Content-Type: application/json' -d '{}')"
	if [[ "$code" == "404 0" ]]; then pass "POST /internal/web/v1/auth/login with a service-token header → 404, not proxied"; else fail "POST /internal/… must be 404 from the edge" "$code"; fi
	code="$(edge_code /)"
	if [[ "$code" == "404 0" ]]; then pass "/ → 404 (no web app yet)"; else fail "/ must be 404" "$code"; fi

	headers="$(curl -sS --max-time 15 "${curl_tls[@]}" -o /dev/null -D - "$base/internal/anything" 2>&1)"
	if grep -qi '^strict-transport-security:' <<<"$headers"; then pass "responses carry Strict-Transport-Security"; else fail "Strict-Transport-Security header is missing"; fi

	code="$(edge_code /api/v1/health)"
	case "${code%% *}" in
	200) pass "/api/v1/health is proxied to the API (200)" ;;
	502 | 503) info "note  /api/v1/health → ${code%% *}: the route is proxied, but the API is not running (start it with --profile app)" ;;
	*) fail "/api/v1/health must be proxied to the API" "$code" ;;
	esac
}

main() {
	need openssl jq
	need_docker
	[[ -f "$ENV_FILE" ]] || die "$ENV_FILE does not exist. Run infra/scripts/bootstrap.sh first."
	resolve_mode "$MODE_FLAG"

	test_services
	test_postgres
	test_network
	test_garage
	test_openbao
	test_edge

	step "Result"
	printf '   %d passed, %d failed, %d skipped\n' "$PASSED" "$FAILED" "$SKIPPED"
	((FAILED == 0))
}

main
