import { spawn } from 'node:child_process';

export interface OcrRequest {
  /** Plaintext PDF to read. Lives on the tmpfs scratch directory. */
  readonly inputPath: string;
  /** Where to write the searchable PDF. */
  readonly outputPath: string;
  /** Where to write the recognised text. */
  readonly sidecarPath: string;
  /** Tesseract languages, e.g. `eng+urd`. Validated by the configuration schema. */
  readonly languages: string;
  /** Scratch directory for the engine's own temporary files (same tmpfs). */
  readonly workDir: string;
  readonly signal: AbortSignal;
}

/** Turns a scanned PDF into a searchable PDF plus its text. */
export interface OcrEngine {
  run(request: OcrRequest): Promise<void>;
}

/** The engine failed. Carries the exit code only: its output could quote the document. */
export class OcrError extends Error {
  public readonly code: string;

  public constructor(code: string) {
    super(`OCR failed (${code}).`);
    this.name = 'OcrError';
    this.code = code;
  }
}

const TIMEOUT_MS = 30 * 60_000;
/**
 * Bounds on the work one uploaded PDF can cause, since OCR runs one job at a
 * time for every user on a small CPU. Pages past the limit are kept as they are
 * (not searchable); a page that takes too long keeps its image without text.
 */
const MAX_OCR_PAGES = 100;
const PAGE_TIMEOUT_SECONDS = 120;
/** Larger images are refused as decompression bombs. An A4 page at 600 dpi is about 35 MP. */
const MAX_IMAGE_MEGAPIXELS = 100;
// A fixed search path: the engine must not depend on (or inherit) the worker's environment.
const ENGINE_PATH = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin';

/**
 * OCRmyPDF with Tesseract, as a child process.
 *
 * - Arguments are passed as an array; no shell is involved.
 * - The child gets a minimal environment. Its temporary files, home and cache
 *   all point at the job's scratch directory, which is on tmpfs: plaintext never
 *   touches a persistent disk, and the worker's root filesystem can be read-only.
 * - Nothing of its output is logged.
 */
export class OcrmypdfEngine implements OcrEngine {
  /** @param command The OCRmyPDF executable. Tests point it at a stand-in. */
  public constructor(private readonly command: string = 'ocrmypdf') {}

  public run(request: OcrRequest): Promise<void> {
    const args = [
      '--language',
      request.languages,
      '--sidecar',
      request.sidecarPath,
      // Pages that already carry text are kept as they are instead of failing the job.
      '--skip-text',
      '--pages',
      `1-${MAX_OCR_PAGES}`,
      '--tesseract-timeout',
      String(PAGE_TIMEOUT_SECONDS),
      '--max-image-mpixels',
      String(MAX_IMAGE_MEGAPIXELS),
      '--output-type',
      'pdf',
      '--optimize',
      '0',
      // One page at a time: the worker shares a small machine and a memory cap with its tmpfs.
      '--jobs',
      '1',
      '--quiet',
      request.inputPath,
      request.outputPath,
    ];
    return new Promise<void>((resolve, reject) => {
      const child = spawn(this.command, args, {
        cwd: request.workDir,
        env: {
          PATH: ENGINE_PATH,
          HOME: request.workDir,
          TMPDIR: request.workDir,
          XDG_CACHE_HOME: request.workDir,
          LANG: 'C.UTF-8',
          OMP_THREAD_LIMIT: '1',
        },
        stdio: 'ignore',
        signal: request.signal,
        timeout: TIMEOUT_MS,
        killSignal: 'SIGKILL',
      });
      child.on('error', (error) => {
        const code = (error as { code?: unknown }).code;
        reject(new OcrError(typeof code === 'string' ? code : 'SPAWN_FAILED'));
      });
      child.on('close', (exitCode, signal) => {
        if (exitCode === 0) {
          resolve();
        } else {
          reject(new OcrError(signal === null ? `EXIT_${exitCode}` : `SIGNAL_${signal}`));
        }
      });
    });
  }
}
