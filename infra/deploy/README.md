# Deploying Scanova to the home server

How Scanova runs on the shared home server (`ssh scanova`, Debian 13,
i5-1235U, 15 GB) and how GitHub Actions deploys to it. The stack itself is
described in [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md) §8.

## Addresses

| Host | Serves |
|---|---|
| `https://scanova.ubaidbinwaris.com` | download page and published APKs (`/downloads/android/`); members' documents in the browser under `/app` (web app) |
| `https://api.scanova.ubaidbinwaris.com` | the API the phone pairs with (`/api/v1/*`), plus the same `/downloads/android/` for the in-app updater |
| `https://admin.scanova.ubaidbinwaris.com` | the admin console (web app, admins only); optionally limited to `SCANOVA_ADMIN_ALLOWED_IPS` |

Pair the phone with `https://api.scanova.ubaidbinwaris.com` and no fingerprint:
the certificates are public (Let's Encrypt).

## How traffic reaches Scanova

The server is shared. HAProxy owns port 443 and routes by TLS SNI without
decrypting; the system Caddy owns port 80.

```
internet :443 ─► router ─► HAProxy (TCP, SNI)
                             ├─ s3 / photos …            → system Caddy (unchanged)
                             ├─ tunnel …                 → unchanged
                             ├─ *.scanova hosts          → 127.0.0.1:9443  Scanova edge (Caddy container)
                             │                              TLS + Let's Encrypt (TLS-ALPN-01),
                             │                              PROXY protocol v2 → real client IPs
                             └─ everything else           → unchanged default
internet :80  ─► system Caddy: http://*.scanova → https:// redirect only
```

Scanova's edge publishes only on `127.0.0.1:9443`. Its data services publish
nothing.

## Object storage

Scanova uses the server's existing Garage (`s3.ubaidbinwaris.com`):

- bucket `scanova-documents`, private (no website access);
- its own access key, allowed to read and write that bucket only (no owner
  rights, cannot create buckets);
- the containers reach Garage through `scanova-s3-relay.socket`, a systemd
  relay that listens only on `10.213.47.1:3900`, the host side of Scanova's
  internal Docker network `s3_net`, and forwards to Garage on `127.0.0.1:3900`.

Garage only ever receives ciphertext (§5.3).

## One-time root setup

`infra/deploy/server-setup.sh` does every root-level step and nothing else. It
is safe to re-run. Copy it to the server with `deploy-gate` and the deploy
public key, then as root:

```
bash server-setup.sh                  # set up or repair
bash server-setup.sh --rotate-s3-key  # new Garage access key; deploy afterwards
```

It creates the `scanova` user (member of `docker`) and `/opt/scanova`. It
installs the deploy gate, and writes `deploy.conf` and `s3.env`. It creates
the bucket and key, sets up the relay, and adds the HAProxy route and the
Caddy redirect. Every HAProxy and Caddy change is validated before reload,
and the previous file is kept as `*.bak.scanova-<time>`.

## The deploy key and the gate

GitHub Actions logs in as `scanova` with a dedicated ed25519 key. The
`authorized_keys` entry is root-owned and forces every login into
`/opt/scanova/bin/deploy-gate`, which accepts exactly:

| Request | Does |
|---|---|
| `receive <id>` | unpacks a release tarball from stdin into `releases/<id>` |
| `activate <id>` | runs that release's `infra/deploy/deploy.sh activate` |
| `rollback [<id>]` | re-activates an earlier release |
| `publish-apk <code> <name> <sha256> <cert> <notes>` | publishes an APK from stdin |
| `status` | current release and published build |

No shell, port forwarding or agent forwarding is possible with that key.
Because `scanova` may run Docker, whoever can deploy can run code as root on
this server. That is true of any deploy pipeline, so protect `main` (branch
protection, and required reviewers on the `production` environment if more
people get write access).

## What a deploy does

`deploy.sh activate <id>` (runs as `scanova`):

1. links the release to the shared `.env` and state;
2. writes this server's settings into `.env` (public TLS mode, host names, port
   9443 on loopback, external S3);
3. runs `bootstrap.sh --prod` (idempotent: certificates, databases, vault);
4. dumps both databases to `shared/backups/main` and `shared/backups/keys`
   (newest 7 kept, each kind in its own folder);
5. builds the images tagged with the release id (also `scanova/ai-image` when
   `AI_IMAGE_PROVIDER=gcdrnet`), migrates, and starts;
6. waits for migrations, api, edge and worker. If anything fails, it starts the
   previous release again and the deploy fails;
7. switches `current`, starts the AI runtimes `.env` turns on (`ai-chat` and
   `ai-embed` when `AI_PROVIDER=openai-compatible`, `ai-image` when
   `AI_IMAGE_PROVIDER=gcdrnet`, each with no model in memory; a runtime that does
   not build or start only logs a warning, it never fails the deploy), and prunes
   old releases and images (newest 5 kept). It never stops a runtime that `.env`
   no longer turns on: see [docs/server-ai-guide.md](../../docs/server-ai-guide.md).

## Android builds

`.github/workflows/android-release.yml` signs the APK with the release keystore
(GitHub secrets) and calls `publish-apk`. `publish-apk.sh` checks the size,
the SHA-256 and the zip header. It then refuses three cases: a build older
than the published one, a different file under a published version code, and
a build signed by another key. Finally it writes `latest.json`, which both the
download page and the in-app updater read.

The keystore and its passwords are in `~/.config/scanova/` on the
workstation that created them. **Back them up offline.** Without them no
installed app can ever update again.

## Operations

```
sudo -u scanova -H bash -c 'cd /opt/scanova/current && infra/scripts/compose.sh --profile app ps'
sudo -u scanova -H bash -c 'cd /opt/scanova/current && infra/scripts/compose.sh --profile app exec -T api node dist/cli.js user:create --email you@example.com --name "You"'
sudo -u scanova -H bash -c 'cd /opt/scanova/current && infra/scripts/compose.sh --profile app exec -T api node dist/cli.js owner:grant --email you@example.com'
sudo -u scanova -H bash -c 'cd /opt/scanova/current && infra/scripts/smoke-test.sh --no-rotate'
```

- Server AI (docs/ARCHITECTURE.md §11.8): `owner:grant` (or the admin console,
  Users → Manage → Owner) gives a member the "Server AI" settings on the app's
  Me tab, with the "Page clean-up" switch. Turned on, it loads the clean-up model
  into memory (refused when that would leave less than `AI_KEEP_FREE_MB` free);
  turned off, or 15 minutes after their app was closed, it unloads it. It needs
  `AI_IMAGE_PROVIDER=gcdrnet` first. The text AI (`AI_PROVIDER`) stays off and
  is not planned. `owner:list` and `owner:revoke` manage owners. Every step,
  with the commands: [docs/server-ai-guide.md](../../docs/server-ai-guide.md).

- Roll back: Actions → Deploy server → Run workflow → `rollback_to` = a
  release id from `/opt/scanova/releases`.
- OpenBao recovery shares: after the first deploy they are in
  `/opt/scanova/shared/state/openbao/recovery/`. Move two of the three off the
  server.
- Backups: `/opt/scanova/shared/backups/` on the same disk is only a safety
  net for a bad migration. Copy `main` and `keys` dumps off the server to
  separate places; a main-database dump is useless without the key database.
