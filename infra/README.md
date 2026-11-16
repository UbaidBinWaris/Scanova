# Scanova infrastructure — operator runbook

How to deploy and run the Scanova server stack on the target machine: an Intel
Core i5 (11th gen) laptop with 16 GB RAM and one 256 GB NVMe drive. The design
it implements is in [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) (§2, §4,
§5.2, §8).

## What is in this directory

| Path | Purpose |
|---|---|
| `compose.yaml` | The stack: `edge`, `postgres`, `garage`, `openbao`; `migrate`, `api`, `worker`, `web` (profile `app`); `ai-chat`, `ai-embed` (profile `ai`, off by default); `ai-fetch`, `ai-eval` (profile `ai-tools`); one-shot `init` and `ai-init`. |
| `compose.dev.yaml` | Development only: publishes PostgreSQL, Garage and OpenBao on `127.0.0.1`. |
| `caddy/`, `postgres/`, `garage/`, `openbao/` | Service configuration. No secrets. |
| `init/prepare-volumes.sh` | What the `init` container does: volume ownership, per-service TLS keys. |
| `scripts/bootstrap.sh` | Fresh clone → running, configured stack. Idempotent. |
| `scripts/smoke-test.sh` | Proves the stack works and is locked down, without the app. |
| `scripts/rotate-credentials.sh` | Rotates the S3 key, the database passwords and the AppRole secret-ids. |
| `scripts/compose.sh` | `docker compose` with the root `.env` and the right files selected. |
| `scripts/reset.sh` | Development teardown. Destroys all data and keys. |
| `.state/` | Created by bootstrap, git-ignored: CA, certificates, unseal key, recovery key shares. |

Memory caps (hard, no swap): edge 256 MB, postgres 2 GB, garage 1 GB, openbao
512 MB, api 1 GB, worker 3 GB, web 512 MB; ai-chat 4.5 GB and ai-embed 1.5 GB
when the `ai` profile runs. Without AI that is 8.3 GB of the 16 GB. Models are
managed with `scripts/ai-models.sh` (fetch, verify, use, eval; see
[docs/ARCHITECTURE.md §11.7](../docs/ARCHITECTURE.md)).

## 1. Prepare the server

**Packages.** Docker Engine with the Compose v2 plugin, `openssl`, `jq`, `curl`.
`qrencode` is optional (prints the pairing QR code). Add the operator's user to
the `docker` group; run the scripts as that user, not as root.

**Encrypt the disk (LUKS).** PostgreSQL WAL and indexes, Garage metadata, Docker
volumes, `.env` and `infra/.state` all hold residue or secrets. On a laptop the
disk must be encrypted. Two ways:

- *Recommended: install the OS with full-disk encryption.* Everything, including
  swap, is then covered and nothing below needs to be relocated.
- *Existing unencrypted install:* give Scanova its own LUKS volume and keep
  **both** Docker's data and this repository on it.

  ```sh
  sudo cryptsetup luksFormat --type luks2 /dev/nvme0n1pN      # the spare partition
  sudo cryptsetup open /dev/nvme0n1pN scanova
  sudo mkfs.ext4 /dev/mapper/scanova
  sudo mkdir -p /srv/scanova && sudo mount /dev/mapper/scanova /srv/scanova
  # /etc/crypttab:  scanova  UUID=<blkid of the partition>  none  luks
  # /etc/fstab:     /dev/mapper/scanova  /srv/scanova  ext4  defaults,noatime  0 2
  # /etc/docker/daemon.json:  { "data-root": "/srv/scanova/docker" }   then restart Docker
  git clone <repository> /srv/scanova/Scanova
  ```

  In this layout the host's swap is outside the encrypted volume: disable it or
  encrypt it. (The containers themselves never swap; Compose forbids it.)

Unlocking at boot is a trade-off. A passphrase typed at the console is the
strongest choice but means the server does not come back by itself after a
power cut. Binding the volume to the TPM (`systemd-cryptenroll
--tpm2-device=auto`) gives unattended boots and still protects a disk that is
removed from the machine, but not a stolen machine that is powered on.

**Laptop specifics.** Stop it from suspending when the lid closes
(`HandleLidSwitch=ignore` in `/etc/systemd/logind.conf`), keep the clock
synchronised (TLS and token expiry depend on it), and allow inbound traffic
only on `SCANOVA_HTTPS_PORT`.

## 2. Deploy

```sh
cd /srv/scanova/Scanova
infra/scripts/bootstrap.sh --public-host 192.168.1.50   # the address phones will use
infra/scripts/smoke-test.sh
infra/scripts/compose.sh --profile app up -d --build     # migrations, api, worker
```

`bootstrap.sh` creates `.env` from `.env.example`, generates every secret,
creates the internal CA and certificates, starts the data services, creates
both databases with their four roles, initialises OpenBao (Transit keys,
policies, AppRoles), creates the Garage bucket and access key, and prints the
pairing details. It ends with a list of what it changed; a second run prints
"Nothing to change".

Edit `.env` for anything else (ports, quotas, rotation periods, `acme` mode)
and run `bootstrap.sh` again: it re-issues the edge certificate when
`SCANOVA_PUBLIC_HOSTS` changes and re-applies the rotation periods.

- **Pairing.** Bootstrap prints the server URL, the CA fingerprint and a QR code
  for `scanova://pair?url=…&ca=…`. Run it again at any time to see them.
- **`SCANOVA_TLS_MODE=acme`.** The edge obtains a public certificate for the
  host in `SCANOVA_PUBLIC_URL` using the TLS-ALPN-01 challenge, so it must be
  reachable from the internet on TCP 443 (`SCANOVA_HTTPS_PORT=443`, or forward
  443 to it). `SCANOVA_ACME_EMAIL` is required. The internal CA is still used
  between the containers.
- **Operator CLI.** `infra/scripts/compose.sh --profile app exec api node dist/cli.js <command>`.
  `owner:grant --email …` gives a member the server AI switch in the app (§11.8).
- **Development machine.** `infra/scripts/bootstrap.sh --dev` also publishes
  PostgreSQL (55432), Garage (53900) and OpenBao (58200) on `127.0.0.1`, which
  are the host-side addresses in `.env`. The choice is remembered.

## 3. After the first bootstrap: secrets you must take care of

Everything sensitive is in two places: the root `.env` and `infra/.state/`.

| File | What it is | What to do |
|---|---|---|
| `infra/.state/openbao/recovery/share-{1,2,3}.key` | OpenBao recovery key shares. Any **2** of them, together with the operator AppRole below, produce a root token. | Copy each share to a **different** offline place (password manager, printed copy in a safe, a second trusted person). Then delete them from the server: `shred -u infra/.state/openbao/recovery/share-*.key`. |
| `infra/.state/openbao/seal/unseal.key` | Static auto-unseal key. With it and the OpenBao volume, the master keys can be read offline. Without it the vault never opens again. | Keep one offline copy, apart from the data backups. See below. |
| `infra/.state/openbao/operator-approle.json` | A login that can do exactly one thing: accept recovery key shares. Useless alone. | Leave it on the server. |
| `infra/.state/pki/ca.key` | Private key of the internal CA that every phone pins. | Back it up offline. It may be removed from the server; it is needed again only when a certificate is re-issued. |
| `.env` | Database passwords, S3 key, AppRole secret-ids, web service token. | Back it up with `infra/.state`. All of it can be rotated. |

**There is no root token.** The one created when OpenBao is initialised lives
only in the memory of `bootstrap.sh` and is revoked before the script ends.
When a script later needs to change vault configuration (`bootstrap.sh` after a
change to policies or rotation periods, `rotate-credentials.sh --approle`), it
logs in as the operator AppRole, submits two recovery key shares, receives a
new root token, uses it and revokes it. Bring the shares back for that moment:

```sh
infra/scripts/bootstrap.sh --recovery-dir /media/usb/scanova-shares
```

Without the shares `bootstrap.sh` still runs: it verifies that both AppRoles
work and leaves the vault configuration as it is.

### Where the unseal key should live

The key is a 32-byte file. OpenBao reads it directly from
`infra/.state/openbao/seal/`, which Compose mounts read-only into the
container. It is never copied into a Docker volume, so it is not inside the
vault's data volume and not inside any volume backup. The `init` container
sets that directory to mode 0750 and the key to 0640 with group id 1000
(OpenBao's group inside the container); `infra/.state` above it stays 0700.

- **Minimum:** the directory is on the LUKS-encrypted disk, and an offline copy
  of the key exists that is stored apart from every data backup. This is what
  bootstrap gives you once the disk is encrypted.
- **Stronger:** keep the key off the data disk. Mount a small removable or
  TPM-unsealed filesystem at `infra/.state/openbao/seal/` holding `unseal.key`
  (group 1000, mode 0640). OpenBao loads the key into memory when it starts
  and does not read the file again, so the medium can be removed afterwards;
  it must be present again for every restart of the container.
- **Never** put the unseal key in the same backup, bucket or drive as a copy of
  the OpenBao volume. Together they are the master keys in plaintext.

Rotating the unseal key is a manual procedure (`previous_key` /
`current_key` in `infra/openbao/openbao.hcl`, described in the OpenBao
documentation for the static seal). The scripts do not automate it.

## 4. Backups

Two sets, stored in different places. A set alone must be useless.

| Set | Contents | Without it |
|---|---|---|
| **Data** | The main database (`pg_dump` of `POSTGRES_DB`), the Garage volumes `scanova_garage_meta` and `scanova_garage_data` (ciphertext only), a Raft snapshot of OpenBao (encrypted by the unseal key). | Documents are gone. |
| **Keys** | The key database (`pg_dump` of `KEYS_POSTGRES_DB`), `infra/.state/` (unseal key, CA, operator AppRole), the recovery key shares, `.env`. | Every document in the data set is unreadable, permanently. |

- **A backup of the main database is useless without a backup of the key
  database.** The main database holds ciphertext and no key at all; every
  wrapped user key and data key is in the key database. Back both up on the
  same schedule, and store the two dumps separately so that one stolen dump
  decrypts nothing.
- A key-database backup must be at least as recent as the data it protects:
  keys are created before the rows they protect, and restoring an older key
  database loses the keys of everything written since.
- The wrapped keys in the key database are themselves only usable through
  OpenBao, so the OpenBao snapshot and the unseal key complete the chain:
  data → key database → OpenBao volume → unseal key.

```sh
# Dumps, as the superuser on the unix socket inside the container.
infra/scripts/compose.sh exec -T postgres pg_dump -U postgres -Fc scanova      > scanova-main.dump
infra/scripts/compose.sh exec -T postgres pg_dump -U postgres -Fc scanova_keys > scanova-keys.dump
```

An OpenBao Raft snapshot needs a root token (`bao operator raft snapshot
save`); take it during a maintenance window with the recovery key shares at
hand, or back up the stopped `scanova_openbao_data` volume instead.

Restore test: on a spare machine, restore both sets, run `bootstrap.sh`, then
`smoke-test.sh --no-rotate`.

## 5. Rotation

| What | How | Effect |
|---|---|---|
| Garage access key | `rotate-credentials.sh --s3` | New key, api/worker recreated, old key deleted. |
| Database passwords (main and key database, runtime and migrator roles) | `rotate-credentials.sh --db` | Four new passwords in `.env`, api/worker recreated. |
| AppRole secret-ids (api, worker, operator) | `rotate-credentials.sh --approle` (needs two recovery key shares) | New secret-ids, old ones destroyed, every AppRole token revoked, api/worker recreated. |
| All of the above | `rotate-credentials.sh --all` | |
| Transit master keys, user keys, signing key | Not an operator task: OpenBao auto-rotates the user-type keys every `KEY_ROTATION_UTK_DAYS` and the signing key every `KEY_ROTATION_JWT_DAYS`; the worker re-wraps (§5.4). | |
| Service certificates (825 days) | Re-run `bootstrap.sh`; it re-issues any certificate with less than 60 days left and restarts that service. The CA key must be on the server for this. | Phones are not affected. |
| Internal CA (10 years) | Replace `infra/.state/pki/ca.*`, re-run `bootstrap.sh`. | Every phone must be paired again. |
| Web service token | Set a new `WEB_SERVICE_TOKEN` in `.env`, recreate the api and the web app. | |
| Web session cookie key | Set a new `WEB_COOKIE_SECRET` in `.env` (`openssl rand -hex 32`), recreate the web app. | Everyone is signed out of the web app; phones are not affected. |

Run `rotate-credentials.sh` after any suspicion that `.env` leaked, and
`smoke-test.sh` afterwards. If the key database was moved to its own
PostgreSQL instance, `--db` rotates only the main database's passwords and says
so; rotate the other two on that instance with `postgres/provision-database.sql`.

The PostgreSQL superuser password is not rotated because it cannot be used:
`pg_hba.conf` rejects the superuser on every network connection, and the
scripts reach it through the unix socket inside the container.

## 6. Moving the key database to its own instance

By default one PostgreSQL instance serves both databases, with separate roles
that cannot connect to each other's database. To separate them physically:

1. Provision the new instance with `postgres/provision-database.sql` (the
   header of that file shows how), with TLS from the same internal CA.
2. Set `KEYS_POSTGRES_HOST` / `KEYS_POSTGRES_PORT` in `.env`, and the two pinned
   lines under `x-key-db-address` in `compose.yaml`.
3. Move the data (`pg_dump` / `pg_restore`), then recreate api and worker.

From then on `bootstrap.sh`, `smoke-test.sh` and `rotate-credentials.sh` treat
the key database as external: they check it at the address in `.env` and do
not provision or rotate it.

## 7. Day-to-day

```sh
infra/scripts/compose.sh ps                       # state and health
infra/scripts/compose.sh logs -f --tail 100 api   # logs (OpenBao's include its audit log)
infra/scripts/compose.sh --profile app up -d --build   # deploy a new version
infra/scripts/compose.sh --profile app stop       # stop everything, keep all data
infra/scripts/smoke-test.sh --no-rotate           # health check that changes nothing
```

After a reboot Docker restarts the containers and OpenBao unseals itself from
the unseal key. If the vault stays sealed, the key file is missing or
unreadable: `infra/scripts/compose.sh logs openbao`.

`infra/scripts/reset.sh` is for development machines. It deletes every
volume and `infra/.state`; on a server that is the loss of all documents.
