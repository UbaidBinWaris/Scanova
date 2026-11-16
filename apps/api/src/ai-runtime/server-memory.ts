import { readFile } from 'node:fs/promises';

/** Free memory of the machine, for the check before the models are loaded (§11.8). */
export interface ServerMemory {
  /** Bytes the kernel can give to new work without swapping, or null when unknown. */
  availableBytes(): Promise<number | null>;
}

/**
 * Reads `MemAvailable` from /proc/meminfo. Inside a container this is still the
 * whole machine's figure (Docker does not virtualise /proc/meminfo), which is
 * what matters: the other services on the server share that memory.
 */
export class ProcMeminfo implements ServerMemory {
  public constructor(private readonly path = '/proc/meminfo') {}

  public async availableBytes(): Promise<number | null> {
    let text: string;
    try {
      text = await readFile(this.path, 'utf8');
    } catch {
      return null;
    }
    const match = /^MemAvailable:\s+(\d+)\s+kB$/mu.exec(text);
    return match?.[1] === undefined ? null : Number(match[1]) * 1024;
  }
}
