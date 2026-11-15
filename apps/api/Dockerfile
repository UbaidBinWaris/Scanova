# syntax=docker/dockerfile:1
#
# Scanova API. Build context: the repository root.
#
#   target runtime  — HTTP API on port 3000, non-root, health at GET /api/v1/health
#   target migrate  — one-shot: applies the migrations of both databases with the
#                     migrator roles, installs the job-queue schema and applies the
#                     table-level grants for the runtime roles
#
# The root .dockerignore keeps .env, infra/.state and everything else that is not
# source out of the build context.

ARG NODE_IMAGE=node:26.13.1-trixie-slim

# ── build: install, generate, compile the whole TypeScript workspace ───────
# Kept identical to the first stage of apps/worker/Dockerfile so both images
# share these layers.
FROM ${NODE_IMAGE} AS build
ENV CI=true \
    PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    TURBO_TELEMETRY_DISABLED=1 \
    CHECKPOINT_DISABLE=1
RUN npm install --global --no-fund --no-audit pnpm@11.8.0
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml turbo.json ./
COPY packages ./packages
COPY apps ./apps
# No BuildKit-only features: these files also build with the classic builder.
RUN pnpm install --frozen-lockfile
RUN pnpm exec turbo run build

# ── deploy: self-contained production trees (compiled code + production dependencies) ─
FROM build AS deploy
RUN pnpm --filter @scanova/api deploy --prod --legacy /out/api \
 && pnpm --filter @scanova/worker deploy --prod --legacy /out/worker

# ── runtime ─────────────────────────────────────────────────────────────────
FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production
WORKDIR /app
COPY --from=deploy /out/api ./
# Runs as the unprivileged `node` user (uid 1000) on a read-only root filesystem:
# the application writes nothing to disk.
USER node
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/api/v1/health').then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
CMD ["node", "dist/main.js"]

# ── migrate ─────────────────────────────────────────────────────────────────
# Needs the Prisma CLI and the migration files, so it is based on the build
# stage rather than on the slim runtime tree.
FROM build AS migrate
ENV NODE_ENV=production \
    HOME=/tmp
WORKDIR /repo/apps/api
USER node
CMD ["node", "dist/migrate.js"]
