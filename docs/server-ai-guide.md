# Server AI: the owner's guide

How the server's owner gives an account access to page clean-up, turns it on,
checks that it works, and turns it off again. Every command is taken from the
scripts in this repository. The design is in
[ARCHITECTURE.md §11.8](ARCHITECTURE.md#118-owner-switches-models-in-memory-only-on-demand);
why this model: [ai-image-research.md](ai-image-research.md).

This is written for the production server: Debian, you log in as root, and
GitHub Actions deploys Scanova into `/opt/scanova`, owned by the user `scanova`
([infra/deploy/README.md](../infra/deploy/README.md)).

The code also has a text AI (chat and embedding models, `AI_PROVIDER`); it is
switched off on this server (`AI_PROVIDER=none`) and not used, so the app reports
it as `DISABLED`.

## Before you start

**Work as `scanova`, in the live release.** `/opt/scanova/current` points at the
live release. Its `.env` is a link to `/opt/scanova/shared/.env`, the one settings
file every release uses. From root:

```sh
su - scanova
cd /opt/scanova/current
```

Every command below runs in that shell. Do not run them as root: a file that root
rewrites (`.env` above all) is no longer readable by `scanova`, and the next
deploy fails. After a deploy, run `cd /opt/scanova/current` again, because the
link then points at a new release.

**Change `.env` with `env_set`** from `infra/scripts/lib.sh`. It rewrites the
shared file in place and keeps it private (mode 0600). Do not use `sed -i`: it
replaces the link with a copy that only this release sees.

```sh
bash -c '. infra/scripts/lib.sh && env_set NAME value'
grep '^NAME=' .env          # to check a setting that is not a secret
```

**Redeploy after every change.** The API gets its settings when its container is
created. On GitHub: **Actions → Deploy server → Run workflow** on `main`, with
`rollback_to` left empty. That runs the tests, sends `main` to the server as a new
release and activates it (`infra/deploy/deploy.sh`: back up, build, migrate,
start, health checks, then the AI runtime). If the new release is unhealthy, it
switches back to the previous one. The deploy's own lines in the log of the
"Activate release" step start with `[deploy]`.

## What page clean-up does

While the owner has **Page clean-up** on (app: **Me → Server AI**), the scan
review and the editor offer a **Server clean-up** look: the flattened page is
sent to the server, cleaned of shadows, colour casts and bleed-through by GCDRNet,
and the cleaned image is used. When clean-up is not available, the page falls back
to the Auto look.

| | |
|---|---|
| Server setting | `AI_IMAGE_PROVIDER=gcdrnet` |
| Service (Compose profile) | `ai-image` (`ai-image`), image `scanova/ai-image:<release id>` built by the deploy |
| Model | GCDRNet, 22 MB, inside the image |
| Memory while off | about 25 MB (measured: 13.7 MiB before the first load, 24 MiB after unloading) |
| Memory while on | about 2 GB for a full 2,400 px page (1.87 GiB measured); cap 3 GiB (`AI_IMAGE_MEMORY_LIMIT`) |
| Time per page | about 7 s for a full page; one page at a time |
| Free memory needed to turn it on | 3 GiB plus `AI_KEEP_FREE_MB` |

The model is in memory only while the switch is on. Turning it off, or 15 minutes
after the owner's app was last open, ends the model's process and gives all of its
memory back. `AI_KEEP_FREE_MB` (default 2048) is the room the API leaves for the
server's other services, so with the defaults page clean-up needs 5 GiB available
to turn on: the "available" column of `free -m`.

## 1. Give an account owner access

Owner access goes to an **active member**: the switch is in the Android app, and
administrators sign in on the web only. Both ways below are recorded in the audit
log (`OWNER_GRANTED`, `OWNER_REVOKED`).

**In the admin console**, signed in as an administrator: Users → **Manage** on the
member → under "Server AI", tick **Owner** → **Save changes**. The list then shows
an "Owner" badge. Untick it to take access away; making the account an
administrator removes it too.

**With the operator CLI:**

```sh
infra/scripts/compose.sh --profile app exec -T api node dist/cli.js owner:grant --email you@example.com
infra/scripts/compose.sh --profile app exec -T api node dist/cli.js owner:list
infra/scripts/compose.sh --profile app exec -T api node dist/cli.js owner:revoke --email you@example.com
```

`--id <user id>` works instead of `--email` (`user:list` shows the ids). An
administrator or an account that is not active is refused.

The owner then finds the switch under **Me → Server AI**. If it does not appear,
close the app completely and open it again: the app remembers that an account is
not an owner until it restarts.

## 2. Turn on page clean-up

1. Set the provider:

   ```sh
   bash -c '. infra/scripts/lib.sh && env_set AI_IMAGE_PROVIDER gcdrnet'
   ```

2. Check the settings:

   ```sh
   grep '^AI_IMAGE_' .env
   bash -c '. infra/scripts/lib.sh && k="$(env_get AI_API_KEY)" && echo "AI_API_KEY: ${#k} characters"'
   ```

   Expected: `AI_IMAGE_PROVIDER=gcdrnet`, `AI_IMAGE_BASE_URL=http://ai-image:8080`,
   `AI_IMAGE_THREADS=4`, `AI_IMAGE_MEMORY_LIMIT=3072m`, and a key of 64
   characters (the runtime's API key; the second command prints only its
   length). If one of these lines or the key is missing, the deploy fills it in:
   its `bootstrap.sh` step adds the settings a newer `.env.example` brings, with
   their defaults, and generates an empty `AI_API_KEY`. Leave the defaults unless
   you have a reason: `AI_IMAGE_THREADS` is the number of cores one page uses,
   and `AI_IMAGE_MEMORY_LIMIT` is the cap (a 2,400 px page peaks at about 2.2 GB;
   the API counts this model as 3 GiB whatever the cap).

3. Redeploy. The log of "Activate release" shows:

   ```
   [deploy] image built: scanova/ai-image:<release id>
   [deploy] page clean-up runtime started (no model in memory until an owner turns it on)
   ```

   A `[deploy] WARNING: …` line instead means page clean-up stays unavailable
   while the rest of Scanova runs normally; see §5.

4. In the app, as the owner: Me → Server AI → **Page clean-up** on. It shows
   Loading, then "Loaded and ready to use". Scan a page and choose the **Server
   clean-up** look.

## 3. Check that it works

The service:

```sh
infra/scripts/compose.sh --profile ai-image ps ai-image     # Up … (healthy)
```

Ask the runtime which model it has and in what state. It publishes no port, so
this runs inside the API container, with the API's own key; it prints no secret:

```sh
infra/scripts/compose.sh --profile app exec -T api node -e '
fetch("http://ai-image:8080/models", { headers: { authorization: `Bearer ${process.env.AI_API_KEY}` } })
  .then(async (r) => console.log(r.status, r.ok ? (await r.json()).data.map((m) => `${m.id}=${m.status.value}`).join(" ") : ""))
  .catch(() => console.log("no answer"));'
```

| Answer | Means |
|---|---|
| `200 gcdrnet=unloaded` | running, switch off |
| `200 gcdrnet=loading` or `200 gcdrnet=loaded` | the owner turned it on |
| `401` | the runtime and the API hold different keys (§5) |
| `no answer` | the runtime is not running, or the API cannot reach it |

The logs:

```sh
infra/scripts/compose.sh --profile ai-image logs --tail 50 ai-image
infra/scripts/compose.sh --profile app logs --tail 200 api | grep -E 'ai_runtime|ai_image'
```

The runtime logs `listening port=8080 model=gcdrnet (not loaded)` when it starts,
`loaded seconds=…` when the owner turns it on, `POST /v1/images/enhance 200` and
`enhanced seconds=… bytes_out=…` for each page, and `unloaded` when it goes off.
It never logs anything about the page itself. The API logs `ai_runtime.auto_off`
(with `"group":"image"`) when the switch turned itself off, and
`ai_runtime.load_failed` or `ai_image.enhance_failed` when something went wrong.

Memory:

```sh
free -m                                                  # "available" is what the API checks
docker stats --no-stream | grep -E 'NAME|scanova-ai-image'
```

In the app, the switch shows its state, when it turns itself off, and the
server's free memory. In the admin console, the audit log (filter by event) shows
`AI_RUNTIME_ON`, `AI_RUNTIME_OFF` and `AI_RUNTIME_AUTO_OFF` with `group` `image`,
and `OWNER_GRANTED` and `OWNER_REVOKED`.

## 4. Turn it off

**For now:** the owner turns **Page clean-up** off in the app. The model's
process ends at once and its memory comes back; the runtime stays up without a
model (about 25 MB). Doing nothing has the same effect 15 minutes after the
owner's app was last open.

**On the server, for good.** The deploy starts `ai-image` when `.env` turns it on,
but never stops it when `.env` no longer does, so stop it yourself after the
deploy:

```sh
bash -c '. infra/scripts/lib.sh && env_set AI_IMAGE_PROVIDER none'
# redeploy; the switch then reports DISABLED. Then, in the new release:
cd /opt/scanova/current
infra/scripts/compose.sh --profile ai-image stop ai-image
infra/scripts/compose.sh --profile ai-image rm -f ai-image
docker image ls scanova/ai-image                         # then docker image rm scanova/ai-image:<tag> for each
```

Each deploy also removes the images of the old releases it prunes.

## 5. Troubleshooting

**No "Server AI" in the app.** The account is not an owner, or is an
administrator: §1, and check with `owner:list`. Then reopen the app.

**The switch shows a problem.** The app shows the state the API reports:

| State | What it means | What to do |
|---|---|---|
| `DISABLED` | `AI_IMAGE_PROVIDER=none`, or `.env` was changed and not deployed since. | §2, then redeploy. |
| `UNAVAILABLE` | The runtime does not answer the API: its container is not running or not healthy, or it holds a different API key. | The checks in §3. Then redeploy, or run `infra/scripts/compose.sh --profile ai-image up -d ai-image` when `docker image ls scanova/ai-image` lists the live release's id. After a key change: below. |
| `FAILED` | The model did not load, or its process ended while cleaning a page (for example out of memory under its cap). | Turn the switch off and on again, and read the runtime's log (`load_failed`, `model_process_ended`). |
| "Not enough memory" when turning on (`NOT_ENOUGH_MEMORY`) | The server's available memory is below 3 GiB plus `AI_KEEP_FREE_MB`; the app shows both numbers. | Wait until other heavy work is done (OCR, a backup, another service); `free -m` shows what is available. Lowering `AI_KEEP_FREE_MB` (then redeploy) takes room from the server's other services. |

**"Server clean-up" is missing, or a page falls back to Auto.** The look is
offered only while page clean-up is on and ready, and it turns itself off 15
minutes after the app was last open: turn it on again. The API refuses a page
with `AI_NOT_READY` when the switch is not ready, with `RATE_LIMITED` when 3
pages are already waiting (one is cleaned at a time, about 7 s for a full page),
and with `SERVICE_UNAVAILABLE` when the runtime failed (its log says
`model_process_ended` or `load_failed`, and the switch shows `FAILED`).

**`[deploy] WARNING: the page clean-up image did not build`.** The deploy hides
the build's output. Build it by hand in the live release to see the error, then
start the runtime:

```sh
docker build -f apps/ai-image/Dockerfile -t "scanova/ai-image:$(basename "$(readlink -f /opt/scanova/current)")" .
infra/scripts/compose.sh --profile ai-image up -d ai-image
```

The build needs internet (Debian updates and the hash-pinned Python wheels).
Start `ai-image` only once its image exists: the service has no build step, so
for a missing image Docker would try to pull `scanova/ai-image` from a public
registry.

**`[deploy] WARNING: the page clean-up runtime did not start`.** Read its log
(§3) and start it by hand: `infra/scripts/compose.sh --profile ai-image up -d ai-image`.

**The deploy failed and the API's log shows `api.config_invalid`.** A setting the
API needs is missing or invalid; the log names the variable, never its value.
The previous release reads the same `.env`, so it may be down too. Fix the
setting (or set `AI_IMAGE_PROVIDER` back to `none`), start the live release again
at once, and redeploy later:

```sh
cd /opt/scanova/current
infra/scripts/compose.sh --profile app up -d
infra/scripts/compose.sh --profile app logs --tail 50 api
```

**After a change of `AI_API_KEY`.** The runtime reads its key file only when it
starts (`ai-init` rewrites the file each time it is brought up). Redeploy so the
API gets the new key, then recreate the runtime:

```sh
infra/scripts/compose.sh --profile ai-image up -d --force-recreate ai-image
```
