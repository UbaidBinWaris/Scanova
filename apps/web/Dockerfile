# syntax=docker/dockerfile:1
#
# Scanova web app (Next.js: member portal + admin console). Build context: the
# repository root.
#
#   target runtime  — Next.js standalone server on port 3000, non-root, read-only
#                     root filesystem, health at GET /healthz
#
# The root .dockerignore keeps .env, infra/.state and everything else that is not
# source out of the build context. Telemetry is off (next.config.ts and below).

ARG NODE_IMAGE=node:26.13.1-trixie-slim

# ── build: identical to the first stage of apps/api/Dockerfile and
#    apps/worker/Dockerfile, so all images share these layers (and the one
#    `turbo run build` that also builds this app) ─────────────────────────────
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

# ── runtime: the standalone server (traced code and production dependencies only) ─
FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
WORKDIR /app
COPY --from=build /repo/apps/web/.next/standalone ./
COPY --from=build /repo/apps/web/.next/static ./apps/web/.next/static
COPY --from=build /repo/apps/web/public ./apps/web/public
# Runs as the unprivileged `node` user (uid 1000) on a read-only root filesystem.
USER node
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/healthz').then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
CMD ["node", "apps/web/server.js"]
