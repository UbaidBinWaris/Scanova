# syntax=docker/dockerfile:1
#
# Scanova worker. Build context: the repository root.
#
#   target runtime  — background jobs (key rotation, OCR, purge), non-root,
#                     with OCRmyPDF and Tesseract (eng, urd)
#
# The root .dockerignore keeps .env, infra/.state and everything else that is not
# source out of the build context.

# Tag for people, digest for the build: the same bytes every time (Dependabot proposes updates).
ARG NODE_IMAGE=node:26.13.1-trixie-slim@sha256:e6bedef217677b276c591795e169840edd9096dbf13f29df8ef8d1856b8820bc

# ── build: identical to the first stage of apps/api/Dockerfile (shared layers) ─
FROM ${NODE_IMAGE} AS build
ENV CI=true \
    PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    TURBO_TELEMETRY_DISABLED=1 \
    CHECKPOINT_DISABLE=1
RUN npm install --global --no-fund --no-audit pnpm@11.8.0
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .pnpmfile.cjs turbo.json ./
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
# OCRmyPDF brings Tesseract and Ghostscript; the language packs are English and Urdu.
# Also: Debian security fixes newer than the base image, and no package managers
# (the worker runs `node` only; npm's bundled libraries are attack surface and CVEs).
RUN apt-get update \
 && apt-get upgrade --yes --no-install-recommends \
 && apt-get install --yes --no-install-recommends \
      ocrmypdf \
      tesseract-ocr-eng \
      tesseract-ocr-urd \
 && rm -rf /var/lib/apt/lists/* \
 && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
           /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
ENV NODE_ENV=production
WORKDIR /app
COPY --from=deploy /out/worker ./
# Runs as the unprivileged `node` user (uid 1000) on a read-only root filesystem.
# The only place it writes is SCANOVA_TMP_DIR, a tmpfs mount, during an OCR job.
USER node
CMD ["node", "dist/main.js"]
