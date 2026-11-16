import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';

// No telemetry, ever: also set for the build itself, which loads this file first.
process.env.NEXT_TELEMETRY_DISABLED = '1';

const workspaceRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const config: NextConfig = {
  // A self-contained server (node server.js) for the Docker image.
  output: 'standalone',
  outputFileTracingRoot: workspaceRoot,
  turbopack: { root: workspaceRoot },
  poweredByHeader: false,
  reactStrictMode: true,
  productionBrowserSourceMaps: false,
  // No image optimisation endpoint: nothing here needs it, and it is attack surface.
  images: { unoptimized: true },
  // The session cookie is the only state; nothing is cached across users.
  experimental: {
    serverActions: { bodySizeLimit: '64kb' },
  },
};

export default config;
