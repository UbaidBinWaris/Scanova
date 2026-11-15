import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { OcrError, OcrmypdfEngine, type OcrRequest } from './ocr-engine.js';

let dir: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'scanova-ocr-engine-'));
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

function request(overrides: Partial<OcrRequest> = {}): OcrRequest {
  return {
    inputPath: join(dir, 'input.pdf'),
    outputPath: join(dir, 'output.pdf'),
    sidecarPath: join(dir, 'text.txt'),
    languages: 'eng+urd',
    workDir: dir,
    signal: new AbortController().signal,
    ...overrides,
  };
}

/** A stand-in executable that records how it was called. */
function standIn(script: string): string {
  const path = join(dir, 'fake-ocrmypdf');
  writeFileSync(path, `#!/bin/sh\n${script}\n`);
  chmodSync(path, 0o755);
  return path;
}

describe('OcrmypdfEngine', () => {
  it('passes the files and languages as arguments, without a shell', async () => {
    const command = standIn(
      'printf "%s\\n" "$@" > "$TMPDIR/args.txt"; env > "$TMPDIR/env.txt"; pwd > "$TMPDIR/cwd.txt"',
    );
    await new OcrmypdfEngine(command).run(request({ languages: 'eng+urd' }));
    const args = readFileSync(join(dir, 'args.txt'), 'utf8').trim().split('\n');
    expect(args).toEqual([
      '--language',
      'eng+urd',
      '--sidecar',
      join(dir, 'text.txt'),
      '--skip-text',
      // Work bounds for untrusted PDFs.
      '--pages',
      '1-100',
      '--tesseract-timeout',
      '120',
      '--max-image-mpixels',
      '100',
      '--output-type',
      'pdf',
      '--optimize',
      '0',
      '--jobs',
      '1',
      '--quiet',
      join(dir, 'input.pdf'),
      join(dir, 'output.pdf'),
    ]);
    expect(readFileSync(join(dir, 'cwd.txt'), 'utf8').trim()).toBe(dir);
  });

  it('gives the engine a minimal environment whose temp, home and cache are the scratch directory', async () => {
    const command = standIn('env > "$TMPDIR/env.txt"');
    await new OcrmypdfEngine(command).run(request());
    const environment = Object.fromEntries(
      readFileSync(join(dir, 'env.txt'), 'utf8')
        .trim()
        .split('\n')
        .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
    );
    // Nothing inherited from the worker: no credentials can leak into the child.
    const inherited = Object.keys(environment).filter(
      (name) =>
        ![
          'PATH',
          'HOME',
          'TMPDIR',
          'XDG_CACHE_HOME',
          'LANG',
          'OMP_THREAD_LIMIT',
          'PWD',
          'SHLVL',
          '_',
          'OLDPWD',
        ].includes(name),
    );
    expect(inherited).toEqual([]);
    expect(environment).toMatchObject({
      HOME: dir,
      TMPDIR: dir,
      XDG_CACHE_HOME: dir,
      OMP_THREAD_LIMIT: '1',
    });
  });

  it('reports a failing engine by exit code only', async () => {
    const command = standIn('echo "secret document words" >&2; exit 2');
    const error = await new OcrmypdfEngine(command)
      .run(request())
      .catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(OcrError);
    expect((error as OcrError).code).toBe('EXIT_2');
    expect((error as OcrError).message).not.toContain('secret');
  });

  it('reports a missing executable and an aborted job', async () => {
    const missing = await new OcrmypdfEngine(join(dir, 'does-not-exist'))
      .run(request())
      .catch((caught: unknown) => caught);
    expect((missing as OcrError).code).toBe('ENOENT');

    const controller = new AbortController();
    const running = new OcrmypdfEngine(standIn('sleep 30')).run(
      request({ signal: controller.signal }),
    );
    setTimeout(() => {
      controller.abort();
    }, 100);
    const aborted = await running.catch((caught: unknown) => caught);
    expect(aborted).toBeInstanceOf(OcrError);
  });
});
