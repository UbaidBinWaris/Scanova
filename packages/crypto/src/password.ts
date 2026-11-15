import { hash, verify } from '@node-rs/argon2';
import { CryptoError } from './errors.js';

export interface Argon2Parameters {
  readonly memoryKib: number;
  readonly timeCost: number;
  readonly parallelism: number;
}

const OUTPUT_BYTES = 32;
// The binding declares these as ambient const enums, which cannot be imported under
// `verbatimModuleSyntax`. The numeric values are part of its stable native interface.
const ALGORITHM_ARGON2ID = 2;
const VERSION_0X13 = 1;
// $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
const ENCODED_PATTERN =
  /^\$argon2id\$v=19\$m=(\d+),t=(\d+),p=(\d+)\$[A-Za-z0-9+/]+\$[A-Za-z0-9+/]+$/u;

/** Argon2id hash in the standard PHC string format, with a fresh random salt. */
export async function hashPassword(
  password: string,
  parameters: Argon2Parameters,
): Promise<string> {
  if (password.length === 0) {
    throw new CryptoError('INVALID_INPUT', 'A password must not be empty.');
  }
  return hash(password, {
    algorithm: ALGORITHM_ARGON2ID,
    version: VERSION_0X13,
    memoryCost: parameters.memoryKib,
    timeCost: parameters.timeCost,
    parallelism: parameters.parallelism,
    outputLen: OUTPUT_BYTES,
  });
}

/** Never throws for a wrong password or a malformed hash: both are simply "no". */
export async function verifyPassword(encodedHash: string, password: string): Promise<boolean> {
  if (!ENCODED_PATTERN.test(encodedHash)) {
    return false;
  }
  try {
    return await verify(encodedHash, password);
  } catch {
    return false;
  }
}

/** True when a stored hash was made with other parameters than the current policy (§5.5). */
export function needsRehash(encodedHash: string, parameters: Argon2Parameters): boolean {
  const match = ENCODED_PATTERN.exec(encodedHash);
  if (match === null) {
    return true;
  }
  return (
    Number(match[1]) !== parameters.memoryKib ||
    Number(match[2]) !== parameters.timeCost ||
    Number(match[3]) !== parameters.parallelism
  );
}
