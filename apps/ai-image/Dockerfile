# syntax=docker/dockerfile:1
#
# Scanova image AI runtime: server-side page clean-up (GCDRNet), §11.8 and
# docs/ai-image-research.md. Build context: the repository root.
#
#   Python standard library + ONNX Runtime, NumPy and Pillow (requirements.txt,
#   every file pinned by hash, wheels only), the 22 MB model in the image, no
#   package manager left behind, non-root, read-only root filesystem.
#
# The root .dockerignore keeps .env, infra/.state, tests and tools out of the
# build context.

# Tag for people, digest for the build (the same image infra/scripts/ai-models.sh pins).
ARG PYTHON_IMAGE=python:3.13.18-slim-trixie@sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90

FROM ${PYTHON_IMAGE} AS runtime
# Debian security fixes newer than the base image.
RUN apt-get update \
 && apt-get upgrade --yes --no-install-recommends \
 && rm -rf /var/lib/apt/lists/*
COPY apps/ai-image/requirements.txt /app/requirements.txt
# Hash-checked wheels only; then pip itself goes (the runtime never installs anything).
RUN python3 -m pip install --no-cache-dir --no-compile --require-hashes --only-binary :all: -r /app/requirements.txt \
 && python3 -m pip uninstall --yes pip
COPY apps/ai-image/server.py /app/server.py
COPY apps/ai-image/model/ /app/model/
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AI_IMAGE_MODEL_PATH=/app/model/gcdrnet.onnx
# The AI runtimes' user (compose x-ids: ai), which can read the shared API key file.
USER 10003:10003
EXPOSE 8080
ENTRYPOINT ["python3", "-I", "/app/server.py"]
CMD ["--api-key-file", "/run/ai-secrets/api-key"]
