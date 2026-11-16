// Copies pdf.js's worker and decoder assets next to the app, so the viewer
// loads them from its own origin (CSP: script/worker-src 'self', no CDN).
// Runs before `next build` and `next dev`; the output is git-ignored.
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const pdfjsRoot = dirname(require.resolve('pdfjs-dist/package.json'));
const target = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'app', 'pdfjs');

rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
cpSync(join(pdfjsRoot, 'build', 'pdf.worker.min.mjs'), join(target, 'pdf.worker.min.mjs'));
for (const directory of ['wasm', 'standard_fonts', 'cmaps']) {
  cpSync(join(pdfjsRoot, directory), join(target, directory), { recursive: true });
}
