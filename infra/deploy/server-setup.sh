#!/usr/bin/env bash
# Scanova — one-time root setup on the shared home server. Safe to re-run.
#
# Run as root (su -) from the folder that holds this script, deploy-gate and
# deploy_ed25519.pub:
#
#   bash server-setup.sh                 set up or repair everything below
#   bash server-setup.sh --rotate-s3-key  replace Scanova's Garage access key
#
# What it does, and nothing else:
#   1. a system user `scanova` (in the docker group) that owns /opt/scanova
#   2. /opt/scanova/bin/deploy-gate (root-owned) and an authorized_keys entry
#      that lets the GitHub Actions deploy key run ONLY that gate
#   3. /opt/scanova/shared/deploy.conf: host names and ports for deploys
#   4. on the existing Garage: a private bucket and an access key that may only
#      read and write that bucket; credentials to /opt/scanova/shared/s3.env
#   5. a relay (systemd socket + systemd-socket-proxyd) that exposes Garage's
#      S3 port only on Scanova's private Docker network address 10.213.47.1
#   6. HAProxy: the three Scanova host names → Scanova's edge on
#      127.0.0.1:9443 (PROXY protocol). Every other route is left as it is.
#   7. system Caddy: http:// → https:// redirect for the three host names
# Every shared config is backed up, validated, and only then reloaded; a config
# that fails validation is restored from the backup.
set -euo pipefail

API_HOST="api.scanova.ubaidbinwaris.com"
SITE_HOST="scanova.ubaidbinwaris.com"
ADMIN_HOST="admin.scanova.ubaidbinwaris.com"
EDGE_PORT=9443
S3_RELAY_IP="10.213.47.1"
S3_RELAY_SUBNET="10.213.47.0/29"
S3_BUCKET="scanova-documents"
S3_KEY_NAME="scanova-app"
GARAGE_CONFIG="/etc/garage.toml"

BASE=/opt/scanova
APP_USER=scanova
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
ROTATE_S3=0

say() { printf '\n== %s\n' "$*"; }
ok() { printf '   ok  %s\n' "$*"; }
warn() { printf '   warning  %s\n' "$*" >&2; }
die() {
	printf '   error  %s\n' "$*" >&2
	exit 1
}

for arg in "$@"; do
	case "$arg" in
	--rotate-s3-key) ROTATE_S3=1 ;;
	-h | --help)
		sed -n '2,24p' "$0"
		exit 0
		;;
	*) die "unknown option $arg" ;;
	esac
done

[[ "$(id -u)" -eq 0 ]] || die "run as root (su -)."
for cmd in docker haproxy caddy garage systemctl ssh-keygen jq; do
	command -v "$cmd" >/dev/null 2>&1 || die "'$cmd' is not installed."
done
PROXYD=""
for candidate in /usr/lib/systemd/systemd-socket-proxyd /lib/systemd/systemd-socket-proxyd; do
	[[ -x "$candidate" ]] && PROXYD="$candidate" && break
done
[[ -n "$PROXYD" ]] || die "systemd-socket-proxyd not found."
[[ -f "$HERE/deploy-gate" ]] || die "deploy-gate is missing next to this script."
[[ -f "$HERE/deploy_ed25519.pub" ]] || die "deploy_ed25519.pub is missing next to this script."
grep -qE '^ssh-ed25519 [A-Za-z0-9+/=]+( |$)' "$HERE/deploy_ed25519.pub" || die "deploy_ed25519.pub is not an ed25519 public key."

# ── 1. User and directories ──────────────────────────────────────────
say "1/7  Deploy user and directories"
if ! id "$APP_USER" >/dev/null 2>&1; then
	useradd --system --home-dir "$BASE" --create-home --shell /bin/bash --comment "Scanova deploys" "$APP_USER"
	ok "created user $APP_USER"
fi
usermod -aG docker "$APP_USER"
chmod 750 "$BASE"
install -d -o "$APP_USER" -g "$APP_USER" -m 750 "$BASE/releases" "$BASE/shared"
install -d -o "$APP_USER" -g "$APP_USER" -m 700 "$BASE/shared/state" "$BASE/shared/backups"
install -d -o root -g root -m 755 "$BASE/bin"
ok "$BASE ready (owner $APP_USER, member of docker)"

# ── 2. Deploy gate and key ───────────────────────────────────────────
say "2/7  Deploy key, restricted to the deploy gate"
install -o root -g root -m 755 "$HERE/deploy-gate" "$BASE/bin/deploy-gate"
# Root-owned, so neither the scanova user nor a deployed release can add keys.
install -d -o root -g root -m 755 "$BASE/.ssh"
key="$(head -n 1 "$HERE/deploy_ed25519.pub" | awk '{print $1" "$2" scanova-deploy@github-actions"}')"
printf 'restrict,command="%s/bin/deploy-gate" %s\n' "$BASE" "$key" >"$BASE/.ssh/authorized_keys"
chown root:root "$BASE/.ssh/authorized_keys"
chmod 644 "$BASE/.ssh/authorized_keys"
if sshd -T 2>/dev/null | grep -qiE '^(allowusers|allowgroups) '; then
	warn "sshd has AllowUsers/AllowGroups: add '$APP_USER' there or deploys cannot log in."
fi
ok "deploy key installed for $APP_USER (forced command: deploy-gate)"

# ── 3. Deployment settings ───────────────────────────────────────────
say "3/7  Deployment settings"
cat >"$BASE/shared/deploy.conf" <<EOF
# Written by server-setup.sh. Read by each release's infra/deploy/deploy.sh.
API_HOST=$API_HOST
SITE_HOST=$SITE_HOST
ADMIN_HOST=$ADMIN_HOST
EDGE_PORT=$EDGE_PORT
S3_ENDPOINT_INTERNAL=http://$S3_RELAY_IP:3900
EOF
chown root:"$APP_USER" "$BASE/shared/deploy.conf"
chmod 640 "$BASE/shared/deploy.conf"
ok "$BASE/shared/deploy.conf"

# ── 4. Garage: private bucket and a key for it alone ─────────────────
say "4/7  Garage bucket and access key"
[[ -r "$GARAGE_CONFIG" ]] || die "$GARAGE_CONFIG not found."
region="$(sed -n 's/^[[:space:]]*s3_region[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' "$GARAGE_CONFIG" | head -n 1)"
[[ -n "$region" ]] || die "no s3_region in $GARAGE_CONFIG."
s3_port="$(sed -n '/^\[s3_api\]/,/^\[/s/^[[:space:]]*api_bind_addr[[:space:]]*=[[:space:]]*"[^"]*:\([0-9]*\)".*/\1/p' "$GARAGE_CONFIG" | head -n 1)"
s3_port="${s3_port:-3900}"

if garage bucket info "$S3_BUCKET" >/dev/null 2>&1; then
	ok "bucket $S3_BUCKET exists"
else
	garage bucket create "$S3_BUCKET" >/dev/null
	ok "created bucket $S3_BUCKET"
fi
# Private: no static-website access, whatever was set before.
garage bucket website --deny "$S3_BUCKET" >/dev/null 2>&1 || true

S3_ENV="$BASE/shared/s3.env"
current_id=""
[[ -f "$S3_ENV" ]] && current_id="$(sed -n 's/^S3_ACCESS_KEY_ID=//p' "$S3_ENV")"
if [[ -n "$current_id" ]] && ((ROTATE_S3 == 0)) && garage key info "$current_id" >/dev/null 2>&1; then
	ok "access key $current_id kept"
	key_id="$current_id"
	secret="$(sed -n 's/^S3_SECRET_ACCESS_KEY=//p' "$S3_ENV")"
else
	out="$(garage key create "$S3_KEY_NAME-$STAMP")"
	key_id="$(grep -oE 'GK[0-9a-f]{24}' <<<"$out" | head -n 1)"
	secret="$(grep -iE 'secret' <<<"$out" | grep -oE '[0-9a-f]{64}' | head -n 1)"
	[[ -n "$key_id" && -n "$secret" ]] || die "could not read the new key from 'garage key create' output."
	ok "created access key $key_id"
fi
# Read and write on this bucket only: no owner rights, no bucket creation.
garage bucket allow --read --write "$S3_BUCKET" --key "$key_id" >/dev/null
garage bucket deny --owner "$S3_BUCKET" --key "$key_id" >/dev/null 2>&1 || true
garage key deny --create-bucket "$key_id" >/dev/null 2>&1 || true
umask 077
cat >"$S3_ENV" <<EOF
S3_REGION=$region
S3_BUCKET=$S3_BUCKET
S3_ACCESS_KEY_ID=$key_id
S3_SECRET_ACCESS_KEY=$secret
EOF
umask 022
chown "$APP_USER:$APP_USER" "$S3_ENV"
chmod 600 "$S3_ENV"
if [[ -n "$current_id" && "$current_id" != "$key_id" ]]; then
	garage key delete --yes "$current_id" >/dev/null 2>&1 && ok "deleted the old key $current_id" ||
		warn "could not delete the old key $current_id (garage key delete --yes $current_id)"
fi
ok "credentials in $S3_ENV (mode 600, owner $APP_USER); the next deploy picks them up"

# ── 5. Relay from Scanova's private network to Garage ────────────────
say "5/7  S3 relay on $S3_RELAY_IP:3900"
cat >/etc/systemd/system/scanova-s3-relay.socket <<EOF
# Scanova: lets containers on Scanova's private Docker network (s3_net,
# $S3_RELAY_SUBNET) reach the local Garage S3 API. Nothing else can connect.
[Unit]
Description=Scanova S3 relay (socket)

[Socket]
ListenStream=$S3_RELAY_IP:3900
# The address appears when Docker creates s3_net; bind before that.
FreeBind=yes
IPAddressAllow=$S3_RELAY_SUBNET
IPAddressDeny=any

[Install]
WantedBy=sockets.target
EOF
cat >/etc/systemd/system/scanova-s3-relay.service <<EOF
[Unit]
Description=Scanova S3 relay to the local Garage
Requires=scanova-s3-relay.socket
After=scanova-s3-relay.socket garage.service

[Service]
ExecStart=$PROXYD --exit-idle-time=10min 127.0.0.1:$s3_port
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
EOF
systemctl daemon-reload
systemctl enable --now scanova-s3-relay.socket >/dev/null
systemctl restart scanova-s3-relay.socket
ok "relay listening (systemctl status scanova-s3-relay.socket)"
# The containers' connections to the relay arrive on the s3_net bridge as
# ordinary incoming traffic, so a firewall that drops incoming traffic by
# default (ufw's DEFAULT_INPUT_POLICY="DROP") drops them too, and every
# upload hangs. Allow exactly that subnet to exactly that address and port.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
	if ufw allow proto tcp from "$S3_RELAY_SUBNET" to "$S3_RELAY_IP" port 3900 comment 'Scanova S3 relay' >/dev/null; then
		ok "firewall (ufw): $S3_RELAY_SUBNET may reach the relay"
	else
		warn "could not add the ufw rule: ufw allow proto tcp from $S3_RELAY_SUBNET to $S3_RELAY_IP port 3900"
	fi
else
	warn "if this host's firewall drops incoming traffic, allow TCP from $S3_RELAY_SUBNET to $S3_RELAY_IP port 3900"
fi

# ── 6. HAProxy: route the Scanova host names to its edge ─────────────
say "6/7  HAProxy route"
HAP=/etc/haproxy/haproxy.cfg
cp -a "$HAP" "$HAP.bak.scanova-$STAMP"
tmp="$(mktemp)"
# Drop an earlier Scanova block, then insert a fresh one before the first
# default_backend of `frontend tls_in` and append the backend.
awk -v api="$API_HOST" -v site="$SITE_HOST" -v admin="$ADMIN_HOST" -v port="$EDGE_PORT" '
	/# BEGIN scanova/ { skip = 1; next }
	/# END scanova/ { skip = 0; next }
	skip { next }
	/^frontend[ \t]+tls_in/ { infront = 1 }
	/^(frontend|backend|listen)[ \t]/ && !/^frontend[ \t]+tls_in/ { infront = 0 }
	infront && /^[ \t]*default_backend/ && !done {
		print "\t# BEGIN scanova (managed by Scanova server-setup.sh)"
		print "\tuse_backend scanova_edge if { req.ssl_sni -i " site " " api " " admin " }"
		print "\t# END scanova"
		done = 1
	}
	{ print }
	END {
		if (!done) exit 3
		print "# BEGIN scanova backend (managed by Scanova server-setup.sh)"
		print "backend scanova_edge"
		print "\t# TLS passes through; PROXY v2 carries the client address to the edge."
		print "\tserver scanova 127.0.0.1:" port " send-proxy-v2"
		print "# END scanova backend"
	}
' "$HAP" >"$tmp" || {
	rm -f "$tmp"
	die "could not find 'default_backend' in 'frontend tls_in' of $HAP; nothing changed."
}
if haproxy -c -q -f "$tmp"; then
	cat "$tmp" >"$HAP"
	rm -f "$tmp"
	systemctl reload haproxy
	ok "HAProxy reloaded (backup: $HAP.bak.scanova-$STAMP)"
else
	rm -f "$tmp"
	die "the new HAProxy config did not validate; $HAP is unchanged."
fi

# ── 7. System Caddy: http → https for the Scanova host names ─────────
say "7/7  HTTP redirect in the system Caddy"
CADDYFILE=/etc/caddy/Caddyfile
cp -a "$CADDYFILE" "$CADDYFILE.bak.scanova-$STAMP"
tmp="$(mktemp)"
awk '/# BEGIN scanova/ { skip = 1; next } /# END scanova/ { skip = 0; next } !skip { print }' "$CADDYFILE" >"$tmp"
cat >>"$tmp" <<EOF

# BEGIN scanova (managed by Scanova server-setup.sh)
http://$SITE_HOST, http://$API_HOST, http://$ADMIN_HOST {
	redir https://{host}{uri} permanent
}
# END scanova
EOF
if caddy validate --config "$tmp" --adapter caddyfile >/dev/null 2>&1; then
	cat "$tmp" >"$CADDYFILE"
	rm -f "$tmp"
	systemctl reload caddy
	ok "Caddy reloaded (backup: $CADDYFILE.bak.scanova-$STAMP)"
else
	rm -f "$tmp"
	die "the new Caddy config did not validate; $CADDYFILE is unchanged."
fi

say "Done"
echo "   Deploy user:  $APP_USER@$(hostname)  (SSH key restricted to $BASE/bin/deploy-gate)"
echo "   Host key:     $(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | awk '{print $2}')"
echo "   Hosts:        https://$SITE_HOST  https://$API_HOST  https://$ADMIN_HOST"
echo "   Next:         the first deploy creates the stack (certificates, databases, vault)."
